package bbbbot.bot;

import bbbbot.domain.BotSession;
import bbbbot.domain.BotTemplate;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.repository.Repositories.BotTemplateRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fuehrt die Zeitplaene der Bot-Vorlagen aus: Zu Beginn eines Termins tritt
 * der Bot selbst bei, zum Ende verlaesst er den Raum wieder (die laufende
 * Aufnahme wird dabei regulaer abgeschlossen und ausgewertet).
 *
 * <p>Pro Termin gibt es genau einen Bot. Ob fuer einen Termin schon einer lief,
 * steht in den Bot-Sessions (Vorlage + Startzeit) - damit uebersteht die Regel
 * auch einen Neustart des Servers:
 * <ul>
 *   <li>noch keine Session im Termin: starten</li>
 *   <li>letzte Session FAILED (Join gescheitert, Server neu gestartet): nach
 *       {@link #RETRY_DELAY} erneut versuchen, hoechstens
 *       {@link #MAX_ATTEMPTS_PER_WINDOW} Mal</li>
 *   <li>letzte Session STOPPED (von Hand beendet, Meeting zu Ende): nichts tun -
 *       wer den Bot wegschickt, will ihn nicht nach 30 Sekunden zurueck</li>
 * </ul>
 */
@Component
public class BotScheduler {

    private static final Logger log = LoggerFactory.getLogger(BotScheduler.class);

    static final int MAX_ATTEMPTS_PER_WINDOW = 5;
    static final Duration RETRY_DELAY = Duration.ofMinutes(2);
    /** Ein Termin, der gleich endet, lohnt keinen Beitritt mehr. */
    static final Duration MIN_REMAINING = Duration.ofMinutes(1);

    private final BotTemplateRepo templateRepo;
    private final BotSessionRepo sessionRepo;
    private final BotManager botManager;
    private final BotTemplateLauncher launcher;

    /** Termin (Beginn), fuer den ein Startproblem schon gemeldet wurde - gegen Log-Flut. */
    private final Map<UUID, Instant> warnedWindows = new ConcurrentHashMap<>();

    public BotScheduler(BotTemplateRepo templateRepo, BotSessionRepo sessionRepo,
                        BotManager botManager, BotTemplateLauncher launcher) {
        this.templateRepo = templateRepo;
        this.sessionRepo = sessionRepo;
        this.botManager = botManager;
        this.launcher = launcher;
    }

    @Scheduled(fixedDelayString = "${bbbbot.bots.schedule-check-ms:30000}",
            initialDelayString = "${bbbbot.bots.schedule-check-ms:30000}")
    public void tick() {
        tick(Instant.now());
    }

    void tick(Instant now) {
        stopExpired(now);
        for (BotTemplate template : templateRepo.findByScheduleEnabledTrue()) {
            try {
                startIfDue(template, now);
            } catch (RuntimeException e) {
                // z.B. alle Bots belegt: im naechsten Durchlauf erneut, gemeldet
                // wird aber nur einmal pro Termin.
                Instant windowKey = BotTemplateLauncher.activeWindow(template, now)
                        .map(ScheduleWindow::start).orElse(now);
                warnOnce(template, windowKey, "Zeitplan: Bot fuer Vorlage {} konnte nicht starten: {}",
                        e.getMessage());
            }
        }
    }

    /** Bots, deren geplantes Ende erreicht ist, verlassen den Raum. */
    private void stopExpired(Instant now) {
        for (BotInstance bot : botManager.listActive()) {
            Instant stopAt = bot.getScheduledStopAt();
            if (stopAt == null || now.isBefore(stopAt) || bot.isShuttingDown()) continue;
            log.info("Zeitplan: Termin von Bot {} ist zu Ende, Bot verlaesst den Raum.", bot.getSessionId());
            botManager.stopBot(bot.getSessionId());
        }
    }

    private void startIfDue(BotTemplate template, Instant now) {
        Optional<ScheduleWindow> active = BotTemplateLauncher.activeWindow(template, now);
        if (active.isEmpty()) return;
        ScheduleWindow window = active.get();
        if (Duration.between(now, window.end()).compareTo(MIN_REMAINING) < 0) return;

        boolean running = botManager.listActive().stream()
                .anyMatch(b -> template.getId().equals(b.getBotTemplateId()));
        if (running) return;

        List<BotSession> sessions = sessionRepo
                .findByBotTemplateIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        template.getId(), window.start());
        if (!sessions.isEmpty()) {
            BotSession latest = sessions.get(0);
            if (latest.getStatus() != BotSession.Status.FAILED) return;
            if (sessions.size() >= MAX_ATTEMPTS_PER_WINDOW) return;
            Instant ended = latest.getEndedAt() != null ? latest.getEndedAt() : latest.getCreatedAt();
            if (now.isBefore(ended.plus(RETRY_DELAY))) return;
        }

        if (botManager.isUrlInUse(template.getMeetingUrl())) {
            // Jemand nimmt den Raum schon auf - ein zweiter Bot waere doppelt.
            warnOnce(template, window.start(), "Zeitplan: Fuer Vorlage {} laeuft bereits ein Bot im Raum{}", "");
            return;
        }

        BotSession session = launcher.start(template, window.end());
        warnedWindows.remove(template.getId());
        log.info("Zeitplan: Bot {} fuer Vorlage '{}' gestartet (Ende {}, Versuch {}).",
                session.getId(), template.getName(), window.end(), sessions.size() + 1);
    }

    private void warnOnce(BotTemplate template, Instant windowKey, String message, String detail) {
        Instant previous = warnedWindows.put(template.getId(), windowKey);
        if (windowKey.equals(previous)) {
            log.debug(message, template.getId(), detail);
        } else {
            log.warn(message, template.getId(), detail);
        }
    }
}
