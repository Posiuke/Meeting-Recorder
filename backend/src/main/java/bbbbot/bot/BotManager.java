package bbbbot.bot;

import bbbbot.config.AppProperties;
import bbbbot.domain.BotSession;
import bbbbot.domain.SummaryChoice;
import bbbbot.recording.RecordingService;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verwaltet alle laufenden Bot-Instanzen (bis zu bbbbot.bots.max-concurrent
 * gleichzeitig - jede Instanz startet eine eigene Chromium-Instanz).
 */
@Service
public class BotManager {

    private static final Logger log = LoggerFactory.getLogger(BotManager.class);

    private final AppProperties props;
    private final SettingsService settings;
    private final RecordingService recordingService;
    private final BotSessionRepo sessionRepo;

    private final Map<UUID, BotInstance> instances = new ConcurrentHashMap<>();

    public BotManager(AppProperties props, SettingsService settings,
                      RecordingService recordingService, BotSessionRepo sessionRepo) {
        this.props = props;
        this.settings = settings;
        this.recordingService = recordingService;
        this.sessionRepo = sessionRepo;
    }

    /**
     * @param sttLanguage Sprache der Spracherkennung fuer die Aufnahmen dieser
     *                    Session; null = Admin-Standard, "auto" = automatisch erkennen
     * @param summary     Auswertungs-Vorlage fuer die Aufnahmen dieser Session
     * @param botTemplateId Bot-Vorlage, aus der gestartet wird (null = keine)
     * @param scheduledStopAt Ende laut Zeitplan; null = kein automatisches Ende
     */
    public synchronized BotSession startBot(String meetingUrl, String botName, boolean autoRecord,
                                            boolean recordVideo, boolean aiAnalysis, boolean diarize,
                                            String sttLanguage, SummaryChoice summary,
                                            UUID botTemplateId, Instant scheduledStopAt,
                                            UUID userId) {
        if (instances.size() >= props.getBots().getMaxConcurrent()) {
            throw new IllegalStateException("Maximale Anzahl gleichzeitiger Bots erreicht ("
                    + props.getBots().getMaxConcurrent() + ")");
        }
        if (isUrlInUse(meetingUrl)) {
            throw new IllegalStateException("Fuer diesen Raum laeuft bereits ein Bot");
        }

        BotSession session = BotSession.create(meetingUrl.trim(), botName, userId, autoRecord, recordVideo, aiAnalysis, diarize);
        session.setSttLanguage(sttLanguage);
        session.setSummaryChoice(summary);
        session.setBotTemplateId(botTemplateId);
        session.setScheduledStopAt(scheduledStopAt);
        sessionRepo.save(session);

        BotConfig config = BotConfig.fromSettings(settings);
        BotInstance instance = new BotInstance(session, config, props.getBots(),
                recordingService, sessionRepo, () -> instances.remove(session.getId()));
        instances.put(session.getId(), instance);
        instance.startAsync();
        log.info("Bot-Session {} gestartet fuer {} (von Nutzer {})", session.getId(), meetingUrl, userId);
        return session;
    }

    public Optional<BotInstance> get(UUID sessionId) {
        return Optional.ofNullable(instances.get(sessionId));
    }

    /** Nimmt gerade eine aktive Bot-Instanz diese Aufnahme auf? (Fuer Loesch-/Aufraeum-Schutz.) */
    /** Bot, dessen laufende Aufnahme zu diesem Stopp-Link gehoert (ohne ihn einzuloesen). */
    public Optional<BotInstance> findByStopToken(String token) {
        String hash = StopTokens.hash(token);
        if (hash == null) return Optional.empty();
        return instances.values().stream().filter(b -> b.hasStopToken(hash)).findFirst();
    }

    /**
     * Loest einen Stopp-Link ein: Die Aufnahme wird verworfen und der Bot
     * verlaesst den Raum. Jeder Link wirkt genau einmal.
     *
     * @return der betroffene Bot, leer bei unbekanntem/verbrauchtem Link
     */
    public Optional<BotInstance> anonymousStop(String token) {
        String hash = StopTokens.hash(token);
        if (hash == null) return Optional.empty();
        for (BotInstance bot : instances.values()) {
            if (bot.claimStopToken(hash)) {
                bot.requestAnonymousStop();
                return Optional.of(bot);
            }
        }
        return Optional.empty();
    }

    /** Laeuft fuer diese Meeting-URL gerade ein Bot (egal von wem)? */
    public boolean isUrlInUse(String meetingUrl) {
        return instances.values().stream()
                .anyMatch(b -> b.getMeetingUrl().equalsIgnoreCase(meetingUrl.trim()));
    }

    public boolean isRecordingActive(UUID recordingId) {
        return instances.values().stream()
                .anyMatch(b -> recordingId.equals(b.getCurrentRecordingId()));
    }

    public List<BotInstance> listActive() {
        return List.copyOf(instances.values());
    }

    public void stopBot(UUID sessionId) {
        BotInstance instance = instances.get(sessionId);
        if (instance != null) {
            instance.shutdownAsync();
        } else {
            // Verwaiste Session (z.B. nach Backend-Neustart) sauber schliessen
            sessionRepo.findById(sessionId).ifPresent(s -> {
                if (s.getStatus() != BotSession.Status.STOPPED && s.getStatus() != BotSession.Status.FAILED) {
                    s.setStatus(BotSession.Status.STOPPED);
                    s.setEndedAt(java.time.Instant.now());
                    sessionRepo.save(s);
                }
            });
        }
    }

    public void startRecording(UUID sessionId) {
        instances.computeIfPresent(sessionId, (id, instance) -> {
            instance.requestRecordingStart();
            return instance;
        });
    }

    public void stopRecording(UUID sessionId, boolean discard) {
        instances.computeIfPresent(sessionId, (id, instance) -> {
            instance.requestRecordingStop(discard);
            return instance;
        });
    }

    @PreDestroy
    public void shutdownAll() {
        log.info("Beende alle {} Bot-Instanzen...", instances.size());
        for (BotInstance instance : instances.values()) {
            try {
                instance.shutdownAsync();
            } catch (RuntimeException e) {
                log.warn("Bot {} konnte nicht sauber beendet werden: {}", instance.getSessionId(), e.getMessage());
            }
        }
    }
}
