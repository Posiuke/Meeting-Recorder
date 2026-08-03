package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.bot.BotInstance;
import bbbbot.bot.BotManager;
import bbbbot.domain.AppUser;
import bbbbot.domain.BotSession;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.settings.SettingsService;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bots")
public class BotController {

    private final BotManager botManager;
    private final BotSessionRepo sessionRepo;
    private final SettingsService settings;

    public BotController(BotManager botManager, BotSessionRepo sessionRepo, SettingsService settings) {
        this.botManager = botManager;
        this.sessionRepo = sessionRepo;
        this.settings = settings;
    }

    @GetMapping
    public List<Dtos.BotView> listActive() {
        AppUser user = CurrentUser.get();
        // Nutzer sehen nur ihre eigenen Bots (inkl. Meeting-URL); Admins alle.
        return botManager.listActive().stream()
                .filter(b -> user.isAdmin() || b.getOwnerId().equals(user.getId()))
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
        if (request.meetingUrl() == null || request.meetingUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting-URL erforderlich");
        }
        String url = request.meetingUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting-URL muss mit http(s):// beginnen");
        }
        requireAllowedHost(url);
        String botName = request.botName() == null || request.botName().isBlank()
                ? "RecorderBot" : request.botName().trim();
        boolean autoRecord = request.autoRecord() == null || request.autoRecord();
        boolean recordVideo = request.recordVideo() != null && request.recordVideo();
        boolean aiAnalysis = request.aiAnalysis() == null || request.aiAnalysis();
        // Diarisierung nur, wenn der Admin sie freigeschaltet hat
        boolean diarize = request.diarize() != null && request.diarize()
                && settings.getBool(SettingsService.WHISPER_DIARIZE);
        try {
            BotSession session = botManager.startBot(url, botName, autoRecord, recordVideo, aiAnalysis, diarize, user.getId());
            BotInstance instance = botManager.get(session.getId()).orElseThrow();
            return toView(instance, user);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
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

    /** Nur der Ersteller des Bots (oder ein Admin) darf ihn steuern. */
    private void requireControl(UUID sessionId) {
        AppUser user = CurrentUser.get();
        UUID ownerId = botManager.get(sessionId).map(BotInstance::getOwnerId)
                .or(() -> sessionRepo.findById(sessionId).map(BotSession::getCreatedBy))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot-Session nicht gefunden"));
        if (!user.isAdmin() && !ownerId.equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur der Ersteller darf diesen Bot steuern");
        }
    }

    /**
     * Schutz gegen SSRF: Ist eine Allowlist konfiguriert (bot.allowedUrlHosts,
     * komma-getrennte Host-Suffixe), muss der Ziel-Host dazu passen. Leer =
     * keine Einschraenkung (Standard, unveraendertes Verhalten).
     */
    private void requireAllowedHost(String url) {
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
                instance.getOwnerId().equals(user.getId())
        );
    }
}
