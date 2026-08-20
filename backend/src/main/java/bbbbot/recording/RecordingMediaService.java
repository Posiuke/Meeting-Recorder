package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.Summary;
import bbbbot.export.ExportFormat;
import bbbbot.export.MarkdownHtml;
import bbbbot.export.TranscriptHtml;
import bbbbot.export.WordDocument;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import bbbbot.stt.TranscriptAssembler;
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
import java.util.List;
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
    private final ParticipantService participantService;
    private final FullAudioService fullAudioService;

    public RecordingMediaService(RecordingSegmentRepo segmentRepo, SummaryRepo summaryRepo,
                                 ParticipantService participantService, FullAudioService fullAudioService) {
        this.segmentRepo = segmentRepo;
        this.summaryRepo = summaryRepo;
        this.participantService = participantService;
        this.fullAudioService = fullAudioService;
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

    /**
     * Durchgehende Tonspur der ganzen Aufnahme zur Wiedergabe im Browser. Range-
     * Requests werden unterstuetzt - erst dadurch kann der Player an eine Stelle
     * springen, ohne die ganze Datei zu laden.
     */
    public ResponseEntity<FileSystemResource> fullAudio(Recording recording) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"aufnahme.mp3\"")
                .body(new FileSystemResource(requireFullAudio(recording)));
    }

    /** Dieselbe Tonspur zum Herunterladen - Dateiname mit Datum und Kurz-Kennung. */
    public ResponseEntity<FileSystemResource> fullAudioDownload(Recording recording) {
        Path path = requireFullAudio(recording);
        String filename = "aufnahme_" + recording.getStartedAt().toString().substring(0, 10)
                + "_" + recording.getId().toString().substring(0, 8) + ".mp3";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new FileSystemResource(path));
    }

    private Path requireFullAudio(Recording recording) {
        return fullAudioService.fullAudio(recording)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Keine abspielbare Tonspur vorhanden"));
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

    /** Aktuelle Fassung der Zusammenfassung als Markdown-Datei. */
    public ResponseEntity<byte[]> summaryDownload(Recording recording) {
        return summaryDownload(recording, ExportFormat.MARKDOWN);
    }

    /**
     * Aktuelle Fassung der Zusammenfassung als Datei - als Markdown (Rohfassung)
     * oder in der Word-Fassung, die sich ohne Umweg weiterreichen laesst.
     */
    public ResponseEntity<byte[]> summaryDownload(Recording recording, ExportFormat format) {
        // Ausgeliefert wird die aktuelle Fassung - dieselbe, die die Anzeige und
        // summary.md zeigen. Aeltere Fassungen sind bewusst kein Download-Ziel.
        Summary summary = summaryRepo.findByRecordingIdAndCurrentIsTrue(recording.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Keine Zusammenfassung vorhanden"));
        byte[] body = format == ExportFormat.WORD
                ? WordDocument.render(displayTitle(recording), subtitle(recording, null),
                        MarkdownHtml.toHtml(summary.getMarkdown()))
                : summary.getMarkdown().getBytes(StandardCharsets.UTF_8);
        return fileResponse(body, format, "zusammenfassung", recording);
    }

    /**
     * Zusammengefuehrtes Transkript als Datei. Es wird aus den Segmenten neu
     * aufgebaut statt aus {@code transcript.md} gelesen: So stimmen die
     * Teilnehmernamen immer mit der Anzeige ueberein, und auch Aufnahmen ohne
     * geschriebene Datei (z.B. nach einem Fehlschlag beim Schreiben) lassen sich
     * herunterladen.
     *
     * @param original true = unveraendertes Whisper-Ergebnis, false = geglaettete
     *                 Fassung, sofern vorhanden
     */
    public ResponseEntity<byte[]> transcriptDownload(Recording recording, boolean original,
                                                     ExportFormat format) {
        List<RecordingSegment> segments = segmentRepo.findByRecordingIdOrderBySeq(recording.getId());
        List<TranscriptAssembler.Entry> entries = TranscriptAssembler.applyNames(
                TranscriptAssembler.assemble(segments, !original),
                participantService.nameMap(recording.getId()));
        if (entries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kein Transkript vorhanden");
        }
        String variant = original ? "original" : "korrigiert";
        byte[] body = format == ExportFormat.WORD
                ? WordDocument.render(displayTitle(recording), subtitle(recording, variant),
                        TranscriptHtml.toHtml(entries))
                : (TranscriptAssembler.toText(entries) + "\n").getBytes(StandardCharsets.UTF_8);
        String base = original ? "transkript_original" : "transkript";
        return fileResponse(body, format, base, recording);
    }

    /**
     * Antwort mit Dateinamen aus Zweck, Aufnahmedatum und Kurz-Kennung - so
     * bleiben mehrere heruntergeladene Aufnahmen im Download-Ordner
     * auseinanderzuhalten.
     */
    private ResponseEntity<byte[]> fileResponse(byte[] body, ExportFormat format, String baseName,
                                                Recording recording) {
        String filename = baseName + "_" + recording.getStartedAt().toString().substring(0, 10)
                + "_" + recording.getId().toString().substring(0, 8) + "." + format.extension();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /** Ueberschrift im Word-Dokument: Titel der Aufnahme, sonst ihr Datum. */
    private static String displayTitle(Recording recording) {
        String title = recording.getTitle();
        return title == null || title.isBlank()
                ? "Aufnahme vom " + recording.getStartedAt().toString().substring(0, 10)
                : title;
    }

    private static String subtitle(Recording recording, String variant) {
        String date = recording.getStartedAt().toString().substring(0, 10);
        return variant == null ? date : date + " · Fassung: " + variant;
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
