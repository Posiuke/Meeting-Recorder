package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.Summary;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Liefert die Dateien einer Aufnahme aus (Audio-Segment, Video, Zusammenfassung
 * als Datei). Die Rechtepruefung passiert vorher beim Aufrufer: Die
 * Weboberflaeche prueft die Leseberechtigung des angemeldeten Nutzers
 * ({@code AccessService}), die oeffentliche Freigabe-Ansicht das vorgelegte
 * Link-Token. Das Ausliefern selbst ist in beiden Faellen identisch und liegt
 * deshalb hier statt doppelt in den Controllern.
 */
@Service
public class RecordingMediaService {

    private final RecordingSegmentRepo segmentRepo;
    private final SummaryRepo summaryRepo;

    public RecordingMediaService(RecordingSegmentRepo segmentRepo, SummaryRepo summaryRepo) {
        this.segmentRepo = segmentRepo;
        this.summaryRepo = summaryRepo;
    }

    /**
     * MP3 eines Segments. ResponseEntity mit Resource unterstuetzt
     * HTTP-Range-Requests (Springen in der Wiedergabe) automatisch.
     */
    public ResponseEntity<FileSystemResource> audio(UUID recordingId, UUID segmentId) {
        RecordingSegment segment = segmentRepo.findById(segmentId)
                .filter(s -> s.getRecordingId().equals(recordingId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Segment nicht gefunden"));
        if (segment.getMp3Path() == null || !Files.exists(Path.of(segment.getMp3Path()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audiodatei nicht vorhanden");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"segment_%03d.mp3\"".formatted(segment.getSeq()))
                .body(new FileSystemResource(segment.getMp3Path()));
    }

    /** Video zur Wiedergabe im Browser (Range-Requests fuer das Springen). */
    public ResponseEntity<FileSystemResource> video(Recording recording) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"meeting.mp4\"")
                .body(new FileSystemResource(requireVideoPath(recording)));
    }

    /** Video zum Herunterladen - Dateiname mit Datum und Kurz-Kennung der Aufnahme. */
    public ResponseEntity<FileSystemResource> videoDownload(Recording recording) {
        String path = requireVideoPath(recording);
        String filename = "meeting_" + recording.getStartedAt().toString().substring(0, 10)
                + "_" + recording.getId().toString().substring(0, 8) + ".mp4";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new FileSystemResource(path));
    }

    /** Neueste fertige Zusammenfassung als Markdown-Datei. */
    public ResponseEntity<byte[]> summaryDownload(Recording recording) {
        Summary summary = summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId()).stream()
                .filter(s -> s.getStatus() == Summary.Status.DONE && s.getMarkdown() != null)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Keine Zusammenfassung vorhanden"));
        String filename = "zusammenfassung_" + recording.getStartedAt().toString().substring(0, 10)
                + "_" + recording.getId().toString().substring(0, 8) + ".md";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(summary.getMarkdown().getBytes(StandardCharsets.UTF_8));
    }

    /** Ist ein abspielbares Video vorhanden? (Datei existiert wirklich) */
    public boolean hasVideo(Recording recording) {
        return recording.getVideoPath() != null && Files.exists(Path.of(recording.getVideoPath()));
    }

    private String requireVideoPath(Recording recording) {
        if (!hasVideo(recording)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video nicht vorhanden");
        }
        return recording.getVideoPath();
    }
}
