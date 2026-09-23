package bbbbot.bot;

import bbbbot.api.BotController;
import bbbbot.domain.BotSession;
import bbbbot.domain.BotTemplate;
import bbbbot.domain.SummaryChoice;
import bbbbot.repository.Repositories.PromptTemplateRepo;
import bbbbot.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Startet einen Bot aus einer Bot-Vorlage - fuer den Button "Bot starten" an der
 * Vorlage wie fuer den {@link BotScheduler}. Beide Wege sollen genau dasselbe
 * tun, deshalb liegt das hier und nicht im Controller.
 *
 * <p>Beim Start wird alles noch einmal geprueft, was sich seit dem Speichern
 * geaendert haben kann: die Host-Allowlist des Admins, die Freigabe der
 * Sprechererkennung und die gewaehlte eigene Promptvorlage.
 */
@Service
public class BotTemplateLauncher {

    /** Praefix der Auswahl einer eigenen Promptvorlage ({@code tpl:<id>}). */
    public static final String OWN_TEMPLATE_PREFIX = "tpl:";

    private final BotManager botManager;
    private final SettingsService settings;
    private final PromptTemplateRepo promptTemplateRepo;

    public BotTemplateLauncher(BotManager botManager, SettingsService settings,
                               PromptTemplateRepo promptTemplateRepo) {
        this.botManager = botManager;
        this.settings = settings;
        this.promptTemplateRepo = promptTemplateRepo;
    }

    /**
     * Startet den Bot der Vorlage.
     *
     * @param scheduledStopAt Ende laut Zeitplan; null = laeuft, bis ihn jemand
     *                        stoppt oder das Meeting endet
     * @throws IllegalStateException wenn kein Bot mehr frei ist oder fuer den
     *                               Raum schon einer laeuft
     */
    public BotSession start(BotTemplate template, Instant scheduledStopAt) {
        String url = BotController.requireMeetingUrl(template.getMeetingUrl(), settings);
        boolean diarize = template.isAiAnalysis() && template.isDiarize()
                && settings.getBool(SettingsService.WHISPER_DIARIZE);
        return botManager.startBot(url, template.getBotName(), template.isAutoRecord(),
                template.isRecordVideo(), template.isAiAnalysis(), diarize,
                template.getSttLanguage(), resolveSummary(template), template.getId(),
                scheduledStopAt, template.getOwnerId());
    }

    /**
     * Start von Hand. Laeuft gerade ein Termin des Zeitplans, gilt der Start als
     * dieser Termin: Der Bot endet dann zur geplanten Endzeit, und der
     * Scheduler startet keinen zweiten.
     */
    public BotSession startNow(BotTemplate template, Instant now) {
        return start(template, activeWindow(template, now).map(ScheduleWindow::end).orElse(null));
    }

    /** Der gerade laufende Termin der Vorlage, falls ihr Zeitplan aktiv ist. */
    public static Optional<ScheduleWindow> activeWindow(BotTemplate template, Instant now) {
        if (!hasSchedule(template)) return Optional.empty();
        return ScheduleWindow.active(template.getScheduleDays(), template.getScheduleStart(),
                template.getScheduleEnd(), zoneOf(template), now);
    }

    /** Der naechste (oder gerade laufende) Termin der Vorlage. */
    public static Optional<ScheduleWindow> nextWindow(BotTemplate template, Instant now) {
        if (!hasSchedule(template)) return Optional.empty();
        return ScheduleWindow.next(template.getScheduleDays(), template.getScheduleStart(),
                template.getScheduleEnd(), zoneOf(template), now);
    }

    private static boolean hasSchedule(BotTemplate template) {
        return template.isScheduleEnabled()
                && template.getScheduleStart() != null
                && template.getScheduleEnd() != null
                && !template.getScheduleDays().isEmpty();
    }

    /** Zeitzone der Vorlage; eine ungueltige oder fehlende faellt auf die des Servers zurueck. */
    static ZoneId zoneOf(BotTemplate template) {
        String zone = template.getScheduleTimeZone();
        if (zone == null || zone.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(zone);
        } catch (java.time.DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /**
     * Auswertungs-Vorlage fuer den Start: Eine eigene Promptvorlage wird frisch
     * gelesen, damit spaetere Aenderungen daran auch hier wirken. Ist sie
     * inzwischen geloescht, bleibt der Stand vom Speichern der Bot-Vorlage.
     */
    SummaryChoice resolveSummary(BotTemplate template) {
        String preset = template.getSummaryPreset();
        if (preset != null && preset.startsWith(OWN_TEMPLATE_PREFIX)) {
            UUID id = parseId(preset.substring(OWN_TEMPLATE_PREFIX.length()));
            if (id != null) {
                Optional<SummaryChoice> live = promptTemplateRepo.findById(id)
                        .filter(p -> p.getOwnerId().equals(template.getOwnerId()))
                        .map(p -> new SummaryChoice(p.getPrompt(), p.getName(),
                                p.getModel(), p.getTemperature()));
                if (live.isPresent()) return live.get();
            }
        }
        return template.getSummaryChoice();
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
