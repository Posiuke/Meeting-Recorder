package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.bot.BotInstance;
import bbbbot.bot.BotManager;
import bbbbot.bot.BotTemplateLauncher;
import bbbbot.domain.AppUser;
import bbbbot.domain.BotSession;
import bbbbot.domain.BotTemplate;
import bbbbot.domain.SummaryChoice;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.BotTemplateRepo;
import bbbbot.settings.SettingsService;
import bbbbot.sharing.BotTemplateAccess;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/bots")
public class BotController {

    /**
     * Standardname, wenn der Aufrufer keinen angibt. Er erscheint in der
     * Teilnehmerliste des Meetings.
     */
    static final String DEFAULT_BOT_NAME = "RecorderBot";

    /**
     * Laenge des Bot-Namens: Die Spalte {@code bot_session.bot_name} traegt mehr,
     * aber ein Name in dieser Groessenordnung ist in der Teilnehmerliste noch
     * lesbar - und ein zu langer soll als 400 auffallen statt beim Insert.
     */
    private static final int MAX_BOT_NAME_LENGTH = 100;

    private final BotManager botManager;
    private final BotSessionRepo sessionRepo;
    private final SettingsService settings;
    private final BotTemplateRepo templateRepo;
    private final BotTemplateLauncher launcher;
    private final BotTemplateAccess templateAccess;
    private final AppUserRepo userRepo;

    public BotController(BotManager botManager, BotSessionRepo sessionRepo, SettingsService settings,
                         BotTemplateRepo templateRepo, BotTemplateLauncher launcher,
                         BotTemplateAccess templateAccess, AppUserRepo userRepo) {
        this.botManager = botManager;
        this.sessionRepo = sessionRepo;
        this.settings = settings;
        this.templateRepo = templateRepo;
        this.launcher = launcher;
        this.templateAccess = templateAccess;
        this.userRepo = userRepo;
    }

    /**
     * Nutzer sehen ihre eigenen Bots (inkl. Meeting-URL) und die Bots von
     * Vorlagen, die mit ihnen geteilt sind - auch die vom Zeitplan gestarteten.
     * Admins sehen alle.
     */
    @GetMapping
    public List<Dtos.BotView> listActive() {
        AppUser user = CurrentUser.get();
        Set<UUID> shared = templateAccess.sharedTemplateIds(user);
        return botManager.listActive().stream()
                .filter(b -> user.isAdmin() || b.getOwnerId().equals(user.getId())
                        || (b.getBotTemplateId() != null && shared.contains(b.getBotTemplateId())))
                .map(b -> toView(b, user))
                .toList();
    }

    @GetMapping("/history")
    public List<Dtos.BotSessionHistoryView> history() {
        AppUser user = CurrentUser.get();
        List<BotSession> sessions = user.isAdmin()
                ? sessionRepo.findTop50ByOrderByCreatedAtDesc()
                : sessionRepo.findTop50ByCreatedByOrderByCreatedAtDesc(user.getId());
        return sessions.stream()
                .map(Dtos.BotSessionHistoryView::of)
                .toList();
    }

    @PostMapping
    public Dtos.BotView start(@RequestBody Dtos.StartBotRequest request) {
        AppUser user = CurrentUser.get();
        String url = requireMeetingUrl(request.meetingUrl(), settings);
        String botName = requireBotName(request.botName());
        boolean autoRecord = request.autoRecord() == null || request.autoRecord();
        boolean recordVideo = request.recordVideo() != null && request.recordVideo();
        boolean aiAnalysis = request.aiAnalysis() == null || request.aiAnalysis();
        // Diarisierung nur, wenn der Admin sie freigeschaltet hat
        boolean diarize = request.diarize() != null && request.diarize()
                && settings.getBool(SettingsService.WHISPER_DIARIZE);
        // Sprache der Spracherkennung: leer = Admin-Standard, "auto" = automatisch erkennen
        String sttLanguage = RecordingController.requireSttLanguage(request.sttLanguage());
        // Auswertungs-Vorlage - dieselben Pruefungen wie beim Upload
        SummaryChoice summary = new SummaryChoice(
                RecordingController.requireSummaryPrompt(request.summaryPrompt()),
                RecordingController.checkTemplateName(request.summaryTemplateName()),
                PromptTemplateController.checkModel(request.summaryModel()),
                PromptTemplateController.checkTemperature(request.summaryTemperature()));
        try {
            BotSession session = botManager.startBot(url, botName, autoRecord, recordVideo, aiAnalysis,
                    diarize, sttLanguage, summary, null, null, RequestOrigin.current(), user.getId());
            return viewOf(session, user);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Startet den Bot einer eigenen oder mit mir geteilten Bot-Vorlage - der
     * kurze Weg "Vorlage waehlen, Bot starten". Laeuft gerade ein Termin ihres
     * Zeitplans, endet der Bot zu dessen Endzeit. Der Bot laeuft immer im Namen
     * des Besitzers der Vorlage, seine Aufnahmen gehoeren also ihm (und werden
     * an die Empfaenger der Vorlage freigegeben).
     */
    @PostMapping("/from-template/{templateId}")
    public Dtos.BotView startFromTemplate(@PathVariable UUID templateId) {
        AppUser user = CurrentUser.get();
        BotTemplate template = templateRepo.findById(templateId)
                .filter(t -> templateAccess.canUse(t, user))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Bot-Vorlage nicht gefunden"));
        try {
            return viewOf(launcher.startNow(template, Instant.now(), RequestOrigin.current()), user);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private Dtos.BotView viewOf(BotSession session, AppUser user) {
        BotInstance instance = botManager.get(session.getId()).orElseThrow();
        return toView(instance, user);
    }

    @DeleteMapping("/{sessionId}")
    public void stop(@PathVariable UUID sessionId) {
        requireControl(sessionId);
        botManager.stopBot(sessionId);
    }

    @PostMapping("/{sessionId}/recording/start")
    public void startRecording(@PathVariable UUID sessionId) {
        requireControl(sessionId);
        botManager.startRecording(sessionId);
    }

    @PostMapping("/{sessionId}/recording/stop")
    public void stopRecording(@PathVariable UUID sessionId,
                              @RequestParam(defaultValue = "false") boolean discard) {
        requireControl(sessionId);
        botManager.stopRecording(sessionId, discard);
    }

    /**
     * Verlaengert einen Bot, der nach dem Zeitplan einer Vorlage laeuft - fuer
     * Termine, die laenger dauern als geplant. Nur fuer laufende Bots mit
     * geplantem Ende.
     */
    @PostMapping("/{sessionId}/schedule/extend")
    public Dtos.BotView extendSchedule(@PathVariable UUID sessionId,
                                       @RequestBody Dtos.ExtendBotScheduleRequest request) {
        requireControl(sessionId);
        java.time.Duration extension = null;
        if (request == null || !Boolean.TRUE.equals(request.untilStopped())) {
            Integer minutes = request == null ? null : request.minutes();
            if (minutes == null || minutes < 1 || minutes > BotManager.MAX_EXTENSION.toMinutes()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Verlaengerung muss zwischen 1 und " + BotManager.MAX_EXTENSION.toMinutes()
                                + " Minuten liegen");
            }
            extension = java.time.Duration.ofMinutes(minutes);
        }
        try {
            botManager.extendScheduledStop(sessionId, extension, Instant.now());
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return toView(botManager.get(sessionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot laeuft nicht")), CurrentUser.get());
    }

    /**
     * Steuern darf der Ersteller des Bots, ein Admin - und wer die Vorlage,
     * aus der der Bot stammt, geteilt bekommen hat.
     */
    private void requireControl(UUID sessionId) {
        AppUser user = CurrentUser.get();
        var instance = botManager.get(sessionId);
        UUID ownerId = instance.map(BotInstance::getOwnerId)
                .or(() -> sessionRepo.findById(sessionId).map(BotSession::getCreatedBy))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot-Session nicht gefunden"));
        if (user.isAdmin() || ownerId.equals(user.getId())) return;
        UUID templateId = instance.map(BotInstance::getBotTemplateId)
                .or(() -> sessionRepo.findById(sessionId).map(BotSession::getBotTemplateId))
                .orElse(null);
        if (templateId != null && templateAccess.sharedTemplateIds(user).contains(templateId)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur der Ersteller darf diesen Bot steuern");
    }

    /**
     * Prueft eine Meeting-URL, wie sie der Bot oeffnen wuerde, und liefert sie
     * ohne umgebende Leerzeichen zurueck. Gemeinsam genutzt vom Sofort-Start und
     * von den Bot-Vorlagen ({@link BotTemplateController}) - eine Vorlage, die
     * beim Starten scheitern wuerde, soll gar nicht erst speicherbar sein.
     */
    public static String requireMeetingUrl(String raw, SettingsService settings) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting-URL erforderlich");
        }
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting-URL muss mit http(s):// beginnen");
        }
        requireAllowedHost(url, settings);
        return url;
    }

    /** Bot-Name: leer bedeutet {@link #DEFAULT_BOT_NAME}. */
    static String requireBotName(String raw) {
        String botName = raw == null || raw.isBlank() ? DEFAULT_BOT_NAME : raw.trim();
        if (botName.length() > MAX_BOT_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bot-Name ist zu lang (max. " + MAX_BOT_NAME_LENGTH + " Zeichen)");
        }
        return botName;
    }

    /**
     * Schutz gegen SSRF: Ist eine Allowlist konfiguriert (bot.allowedUrlHosts,
     * komma-getrennte Host-Suffixe), muss der Ziel-Host dazu passen. Leer =
     * keine Einschraenkung (Standard, unveraendertes Verhalten).
     */
    private static void requireAllowedHost(String url, SettingsService settings) {
        String allowed = settings.get(SettingsService.BOT_ALLOWED_URL_HOSTS).trim();
        if (allowed.isBlank()) return;
        String host;
        try {
            host = new URI(url).getHost();
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting-URL ist ungueltig");
        }
        if (host == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting-URL enthaelt keinen Host");
        }
        if (!hostMatches(host, allowed)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Meeting-URL-Host ist nicht in der erlaubten Liste (bot.allowedUrlHosts)");
        }
    }

    /**
     * Prueft, ob {@code host} zur Allowlist passt: exakte Uebereinstimmung oder
     * Subdomain eines Eintrags. Leere Allowlist = alles erlaubt.
     */
    static boolean hostMatches(String host, String allowedCsv) {
        if (allowedCsv == null || allowedCsv.isBlank()) return true;
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(allowedCsv.split(","))
                .map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(s -> !s.isBlank())
                .anyMatch(s -> h.equals(s) || h.endsWith("." + s));
    }

    private Dtos.BotView toView(BotInstance instance, AppUser user) {
        BotSession session = sessionRepo.findById(instance.getSessionId()).orElse(null);
        return new Dtos.BotView(
                instance.getSessionId(),
                instance.getStatus().name(),
                instance.getMeetingUrl(),
                instance.getRoomName(),
                instance.getBotName(),
                session == null || session.isAutoRecord(),
                session != null && session.isRecordVideo(),
                session == null || session.isAiAnalysis(),
                instance.getCurrentRecordingId(),
                instance.getCurrentOthers(),
                instance.getCurrentAudioTracks(),
                instance.getLastError(),
                session == null ? null : session.getCreatedAt(),
                instance.getOwnerId().equals(user.getId()),
                instance.getBotTemplateId(),
                instance.getScheduledStopAt(),
                instance.getOwnerId().equals(user.getId()) ? null
                        : userRepo.findById(instance.getOwnerId()).map(AppUser::getDisplayName).orElse(null)
        );
    }
}
