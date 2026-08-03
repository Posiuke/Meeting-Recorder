package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.recording.RecordingService;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.settings.SettingsService;
import bbbbot.sharing.AccessService;
import bbbbot.stt.TranscriptAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Direkte Transkription per API: Datei hochladen, Transkript abholen. Gedacht
 * fuer Skripte, die nur den Text brauchen - es wird bewusst KEINE Zusammenfassung
 * erstellt (das kostet GPU-Zeit, die niemand bestellt hat).
 *
 * <p>Der Auftrag laeuft ueber dieselbe Verarbeitungsstrecke wie ein Upload
 * (Transkodierung -> Whisper -> KI-Glaettung mit dem Glossar des Nutzers) und ist
 * deshalb nicht sofort fertig: {@code POST} liefert eine Auftrags-ID, den Zustand
 * holt {@code GET} ab. Fuer kurze Dateien kann der Aufrufer mit {@code ?wait=}
 * gleich auf das Ergebnis warten.
 *
 * <p>Jeder Auftrag ist eine ganz normale Aufnahme im Konto des Nutzers - sichtbar
 * in der Weboberflaeche und loeschbar per {@code DELETE /api/recordings/{id}}.
 * Bewusst kein automatisches Aufraeumen: Ein Transkript, das ein Skript verloren
 * hat, soll nicht unwiederbringlich weg sein.
 */
@RestController
@RequestMapping("/api/transcriptions")
public class TranscriptionController {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionController.class);

    /** Obergrenze fuers Warten in einem Aufruf - danach muss gepollt werden. */
    static final int MAX_WAIT_SECONDS = 600;

    private static final long POLL_INTERVAL_MS = 1000;

    private final RecordingService recordingService;
    private final RecordingRepo recordingRepo;
    private final RecordingSegmentRepo segmentRepo;
    private final ProcessingJobRepo jobRepo;
    private final AccessService access;
    private final SettingsService settings;

    public TranscriptionController(RecordingService recordingService, RecordingRepo recordingRepo,
                                   RecordingSegmentRepo segmentRepo, ProcessingJobRepo jobRepo,
                                   AccessService access, SettingsService settings) {
        this.recordingService = recordingService;
        this.recordingRepo = recordingRepo;
        this.segmentRepo = segmentRepo;
        this.jobRepo = jobRepo;
        this.access = access;
        this.settings = settings;
    }

    /**
     * Nimmt eine Audio-/Videodatei an und startet die Transkription.
     *
     * @param wait Sekunden, die die Antwort auf das Ergebnis warten darf
     *             (0 = sofort antworten, max. {@value #MAX_WAIT_SECONDS})
     * @return 200 mit Transkript, wenn es innerhalb von {@code wait} fertig wurde,
     *         sonst 202 mit der Auftrags-ID
     */
    @PostMapping
    public ResponseEntity<Dtos.TranscriptionView> create(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "diarize", defaultValue = "false") boolean diarize,
            @RequestParam(value = "wait", defaultValue = "0") int wait) {
        AppUser user = CurrentUser.get();
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keine Datei uebermittelt");
        }
        String original = RecordingController.requireSupportedFilename(file.getOriginalFilename());
        boolean diarizeEffective = diarize && settings.getBool(SettingsService.WHISPER_DIARIZE);
        if (diarize && !diarizeEffective) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Sprechererkennung ist auf diesem Server nicht freigeschaltet");
        }

        Recording recording;
        try (InputStream in = file.getInputStream()) {
            recording = recordingService.createUploadedRecording(user.getId(),
                    RecordingService.UploadOptions.forTranscription(title, diarizeEffective),
                    original, in);
        } catch (IOException e) {
            log.error("Transkriptions-Datei '{}' konnte nicht gespeichert werden", original, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Datei konnte nicht gespeichert werden: " + e.getMessage());
        }
        log.info("Transkriptions-Auftrag {} von {} angenommen ({}, warten={}s)",
                recording.getId(), user.getUsername(), original, clampWait(wait));

        Dtos.TranscriptionView view = awaitResult(recording.getId(), clampWait(wait));
        return "DONE".equals(view.status())
                ? ResponseEntity.ok(view)
                : ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    /**
     * Zustand und - sobald fertig - Transkript eines Auftrags. Die Auftrags-ID ist
     * die Aufnahme-ID; es funktioniert daher auch fuer Aufnahmen, die nicht ueber
     * diese Schnittstelle entstanden sind.
     *
     * @param wait Sekunden, die die Antwort auf das Ergebnis warten darf
     */
    @GetMapping("/{id}")
    public Dtos.TranscriptionView get(@PathVariable UUID id,
                                      @RequestParam(value = "wait", defaultValue = "0") int wait) {
        AppUser user = CurrentUser.get();
        access.requireReadable(id, user);
        return awaitResult(id, clampWait(wait));
    }

    private static int clampWait(int wait) {
        return Math.max(0, Math.min(wait, MAX_WAIT_SECONDS));
    }

    /**
     * Wartet hoechstens {@code waitSeconds} auf einen Endzustand. Nicht elegant,
     * aber genau das, was ein Skript fuer eine kurze Datei braucht - und die
     * Obergrenze verhindert, dass Anfragen ewig einen Thread belegen.
     */
    private Dtos.TranscriptionView awaitResult(UUID recordingId, int waitSeconds) {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (true) {
            Dtos.TranscriptionView view = currentState(recordingId);
            if (isFinal(view.status()) || System.currentTimeMillis() >= deadline) return view;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return view;
            }
        }
    }

    private static boolean isFinal(String status) {
        return "DONE".equals(status) || "FAILED".equals(status);
    }

    private Dtos.TranscriptionView currentState(UUID recordingId) {
        Recording recording = recordingRepo.findById(recordingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Transkriptions-Auftrag nicht gefunden"));

        ProcessingJob latest = jobRepo.findByRecordingIdOrderByCreatedAtDesc(recordingId).stream()
                .findFirst().orElse(null);
        if (recording.getStatus() == Recording.Status.FAILED
                || recording.getStatus() == Recording.Status.DISCARDED) {
            String error = recording.getDiscardReason() != null ? recording.getDiscardReason()
                    : latest != null ? latest.getLastError() : null;
            return failed(recording, error);
        }
        if (latest != null && latest.getStatus() == ProcessingJob.Status.FAILED) {
            return failed(recording, latest.getLastError());
        }

        boolean transcriptReady = recording.getStatus() == Recording.Status.TRANSCRIBED
                || recording.getStatus() == Recording.Status.DONE;
        if (!transcriptReady) {
            String status = recording.getStatus() == Recording.Status.PROCESSING ? "RUNNING" : "PENDING";
            return new Dtos.TranscriptionView(recordingId, status, null, null,
                    recording.getDurationMs(), null);
        }

        List<RecordingSegment> segments = segmentRepo.findByRecordingIdOrderBySeq(recordingId);
        // Geglaettete Fassung, wo vorhanden - das ist die Fassung, die auch die
        // Weboberflaeche und die Zusammenfassung verwenden.
        List<TranscriptAssembler.Entry> entries = TranscriptAssembler.assemble(segments, true);
        return new Dtos.TranscriptionView(recordingId, "DONE",
                TranscriptAssembler.toText(entries),
                entries.stream()
                        .map(e -> new Dtos.TranscriptEntry(e.startSeconds(), e.speaker(), e.text()))
                        .toList(),
                recording.getDurationMs(), null);
    }

    private static Dtos.TranscriptionView failed(Recording recording, String error) {
        return new Dtos.TranscriptionView(recording.getId(), "FAILED", null, null,
                recording.getDurationMs(),
                error == null || error.isBlank() ? "Transkription fehlgeschlagen" : error);
    }
}
