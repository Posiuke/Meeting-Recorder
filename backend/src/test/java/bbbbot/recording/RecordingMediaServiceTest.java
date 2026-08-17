package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.export.ExportFormat;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Download des Transkripts: Fassung, Format, Dateiname und Teilnehmernamen. */
class RecordingMediaServiceTest {

    private RecordingSegmentRepo segmentRepo;
    private ParticipantService participantService;
    private RecordingMediaService media;
    private Recording recording;

    @BeforeEach
    void setUp() {
        segmentRepo = mock(RecordingSegmentRepo.class);
        SummaryRepo summaryRepo = mock(SummaryRepo.class);
        participantService = mock(ParticipantService.class);
        media = new RecordingMediaService(segmentRepo, summaryRepo, participantService);

        recording = Recording.start(null, UUID.randomUUID(), null, "/tmp/x", false, true, true);
        recording.setTitle("Wochenbesprechung Technik");

        RecordingSegment segment = RecordingSegment.create(recording.getId(), 0, "/tmp/x/0.webm");
        segment.setStatus(RecordingSegment.Status.READY);
        segment.setTranscriptText("SPEAKER_00:\n[00:05] ähm also guten morgen");
        segment.setCorrectedText("SPEAKER_00:\n[00:05] Guten Morgen.");
        when(segmentRepo.findByRecordingIdOrderBySeq(recording.getId())).thenReturn(List.of(segment));
        when(participantService.nameMap(recording.getId())).thenReturn(Map.of("SPEAKER_00", "Frau Meier"));
    }

    @Test
    void geglaetteteFassungAlsMarkdownMitTeilnehmernamen() {
        ResponseEntity<byte[]> response = media.transcriptDownload(recording, false, ExportFormat.MARKDOWN);

        assertThat(body(response)).isEqualTo("[00:05] Frau Meier: Guten Morgen.\n");
        assertThat(filename(response)).startsWith("transkript_").endsWith(".md");
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/markdown");
    }

    @Test
    void originalfassungLiefertDasWhisperErgebnis() {
        ResponseEntity<byte[]> response = media.transcriptDownload(recording, true, ExportFormat.MARKDOWN);

        assertThat(body(response)).contains("ähm also guten morgen");
        assertThat(filename(response)).startsWith("transkript_original_");
    }

    @Test
    void wordFassungIstEinOeffenbaresDokument() {
        ResponseEntity<byte[]> response = media.transcriptDownload(recording, false, ExportFormat.WORD);

        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/msword");
        assertThat(filename(response)).endsWith(".doc");
        assertThat(body(response))
                .contains("<h1>Wochenbesprechung Technik</h1>")
                .contains("<h3>Frau Meier</h3>")
                .contains("Guten Morgen.")
                .contains("Fassung: korrigiert");
    }

    @Test
    void ohneTranskriptGibtEs404() {
        when(segmentRepo.findByRecordingIdOrderBySeq(any())).thenReturn(List.of());

        assertThatThrownBy(() -> media.transcriptDownload(recording, false, ExportFormat.MARKDOWN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Kein Transkript");
    }

    private static String body(ResponseEntity<byte[]> response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    private static String filename(ResponseEntity<byte[]> response) {
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        return disposition == null ? "" : disposition.replaceAll(".*filename=\"([^\"]+)\".*", "$1");
    }
}
