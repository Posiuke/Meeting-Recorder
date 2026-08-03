package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.domain.Recording;
import bbbbot.recording.CaptureService;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/**
 * Bildschirmaufnahme direkt aus dem Tool: Der Browser des Nutzers zeichnet mit
 * getDisplayMedia/MediaRecorder auf und schiebt die laufende Aufnahme in kurzen
 * Stuecken hierher. Der Server haengt sie an eine Datei an und uebergibt sie am
 * Ende an dieselbe Verarbeitung wie einen Datei-Upload.
 *
 * <p>Voraussetzung im Browser: sicherer Kontext (HTTPS) - siehe docs/SCREEN_CAPTURE.md.
 */
@RestController
@RequestMapping("/api/recordings/capture")
public class CaptureController {

    private final CaptureService captureService;
    private final AppUserRepo userRepo;
    private final SettingsService settings;

    public CaptureController(CaptureService captureService, AppUserRepo userRepo, SettingsService settings) {
        this.captureService = captureService;
        this.userRepo = userRepo;
        this.settings = settings;
    }

    /** Start-Parameter der Aufnahme (wie beim Upload, plus Format des Browsers). */
    public record StartCaptureRequest(String title, Boolean aiAnalysis, Boolean processNow,
                                      Boolean diarize, Boolean video, String mimeType) {}

    /** Rahmenbedingungen fuers Frontend. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        CaptureService.CaptureConfig config = captureService.config();
        return Map.of(
                "enabled", config.enabled(),
                "maxBytes", config.maxBytes(),
                "diarizeAllowed", config.diarizeAllowed());
    }

    @PostMapping("/start")
    public Dtos.RecordingView start(@RequestBody StartCaptureRequest request) {
        AppUser user = CurrentUser.get();
        boolean aiAnalysis = request.aiAnalysis() == null || request.aiAnalysis();
        boolean processNow = aiAnalysis && Boolean.TRUE.equals(request.processNow());
        // Diarisierung nur, wenn der Admin sie freigeschaltet hat
        boolean diarize = Boolean.TRUE.equals(request.diarize())
                && settings.getBool(SettingsService.WHISPER_DIARIZE);
        try {
            Recording recording = captureService.start(user.getId(), request.title(), aiAnalysis,
                    processNow, diarize, Boolean.TRUE.equals(request.video()), request.mimeType());
            return toView(recording, user);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Aufnahme konnte nicht angelegt werden: " + e.getMessage());
        }
    }

    /**
     * Naechstes Stueck der laufenden Aufnahme (Rohdaten im Body). Die Reihenfolge
     * ist zwingend; bei einer Luecke antwortet der Server mit 409 und der
     * erwarteten Nummer, damit der Client von dort neu senden kann.
     */
    @PostMapping(value = "/{id}/chunk", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Map<String, Object> chunk(@PathVariable UUID id, @RequestParam int seq,
                                     HttpServletRequest request) {
        AppUser user = CurrentUser.get();
        try (InputStream body = request.getInputStream()) {
            int nextSeq = captureService.append(id, user.getId(), seq, body);
            return Map.of("nextSeq", nextSeq);
        } catch (CaptureService.SequenceMismatchException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    e.getMessage() + " (expectedSeq=" + e.expectedSeq() + ")");
        } catch (CaptureService.CaptureTooLargeException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Aufnahmedaten konnten nicht gespeichert werden: " + e.getMessage());
        }
    }

    /** Lebenszeichen (z.B. waehrend einer Pause), damit die Aufnahme nicht als abgebrochen gilt. */
    @PostMapping("/{id}/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        try {
            captureService.heartbeat(id, user.getId());
            return Map.of("active", true);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    @PostMapping("/{id}/stop")
    public Dtos.RecordingView stop(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        try {
            return toView(captureService.stop(id, user.getId()), user);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    @PostMapping("/{id}/abort")
    public void abort(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        try {
            captureService.abort(id, user.getId());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    private Dtos.RecordingView toView(Recording recording, AppUser user) {
        AppUser owner = userRepo.findById(recording.getOwnerId()).orElse(null);
        return Dtos.RecordingView.of(recording, recording.getOwnerId().equals(user.getId()), owner);
    }
}
