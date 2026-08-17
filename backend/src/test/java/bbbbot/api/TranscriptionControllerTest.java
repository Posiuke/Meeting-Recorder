package bbbbot.api;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Die Schnittstelle "Datei rein, Transkript raus": geprueft werden die Zustaende,
 * die ein aufrufendes Skript unterscheiden muss, und dass keine Zusammenfassung
 * bestellt wird.
 */
class TranscriptionControllerTest {

    private RecordingService recordingService;
    private RecordingRepo recordingRepo;
    private RecordingSegmentRepo segmentRepo;
    private ProcessingJobRepo jobRepo;
    private SettingsService settings;
    private TranscriptionController controller;

    private AppUser user;
    private Recording recording;

    @BeforeEach
    void setup() {
        recordingService = mock(RecordingService.class);
        recordingRepo = mock(RecordingRepo.class);
        segmentRepo = mock(RecordingSegmentRepo.class);
        jobRepo = mock(ProcessingJobRepo.class);
        AccessService access = mock(AccessService.class);
        settings = mock(SettingsService.class);
        controller = new TranscriptionController(recordingService, recordingRepo, segmentRepo,
                jobRepo, access, settings);

        user = AppUser.create("skripter", "Skripterin", "skript@example.org");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        recording = Recording.start(null, user.getId(), null, "/tmp/x", false, true, false);
        recording.setSource(Recording.Source.UPLOAD);
        when(recordingRepo.findById(recording.getId())).thenReturn(Optional.of(recording));
        when(jobRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId())).thenReturn(List.of());
        when(access.requireReadable(eq(recording.getId()), any())).thenReturn(recording);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "audio/mpeg", "abc".getBytes());
    }

    @Test
    void nimmtDateiAnUndBestelltNurDieTranskription() throws Exception {
        when(recordingService.createUploadedRecording(eq(user.getId()), any(), anyString(), any()))
                .thenReturn(recording);

        var response = controller.create(file("besprechung.mp3"), "Mein Titel", false, null, 0);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("PENDING");
        assertThat(response.getBody().id()).isEqualTo(recording.getId());

        var options = org.mockito.ArgumentCaptor.forClass(RecordingService.UploadOptions.class);
        verify(recordingService).createUploadedRecording(eq(user.getId()), options.capture(),
                eq("besprechung.mp3"), any());
        assertThat(options.getValue().transcribeOnly()).isTrue();
        assertThat(options.getValue().processNow()).isTrue();
        // Kein Video umwandeln - die Rechenzeit bestellt hier niemand
        assertThat(options.getValue().keepVideo()).isFalse();
        assertThat(options.getValue().title()).isEqualTo("Mein Titel");
    }

    @Test
    void reichtDieSpracheDerSpracherkennungWeiter() throws Exception {
        when(recordingService.createUploadedRecording(eq(user.getId()), any(), anyString(), any()))
                .thenReturn(recording);

        controller.create(file("interview.mp3"), null, false, "EN", 0);

        var options = org.mockito.ArgumentCaptor.forClass(RecordingService.UploadOptions.class);
        verify(recordingService).createUploadedRecording(eq(user.getId()), options.capture(),
                anyString(), any());
        assertThat(options.getValue().sttLanguage()).isEqualTo("en");
    }

    @Test
    void weistUnsinnigeSprachangabeAb() {
        assertThatThrownBy(() -> controller.create(file("a.mp3"), null, false, "englisch bitte", 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Sprachangabe");
    }

    @Test
    void weistNichtUnterstuetzteDateitypenAb() {
        assertThatThrownBy(() -> controller.create(file("tabelle.xlsx"), null, false, null, 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Dateityp nicht unterstuetzt");
    }

    @Test
    void weistLeereDateiAb() {
        var empty = new MockMultipartFile("file", "leer.mp3", "audio/mpeg", new byte[0]);

        assertThatThrownBy(() -> controller.create(empty, null, false, null, 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Keine Datei");
    }

    @Test
    void sagtDeutlichWennSprechererkennungNichtFreigeschaltetIst() {
        when(settings.getBool(SettingsService.WHISPER_DIARIZE)).thenReturn(false);

        assertThatThrownBy(() -> controller.create(file("a.mp3"), null, true, null, 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Sprechererkennung");
    }

    @Test
    void meldetLaufendeVerarbeitung() {
        recording.setStatus(Recording.Status.PROCESSING);

        assertThat(controller.get(recording.getId(), 0).status()).isEqualTo("RUNNING");
    }

    @Test
    void liefertDasTranskriptSobaldTranskribiert() {
        recording.setStatus(Recording.Status.TRANSCRIBED);
        recording.setDurationMs(65_000L);
        when(segmentRepo.findByRecordingIdOrderBySeq(recording.getId()))
                .thenReturn(List.of(segment("[00:05] Guten Morgen.", null)));

        Dtos.TranscriptionView view = controller.get(recording.getId(), 0);

        assertThat(view.status()).isEqualTo("DONE");
        assertThat(view.text()).contains("Guten Morgen.");
        assertThat(view.entries()).isNotEmpty();
        assertThat(view.durationMs()).isEqualTo(65_000L);
        assertThat(view.error()).isNull();
    }

    @Test
    void bevorzugtDieGeglaetteteFassung() {
        recording.setStatus(Recording.Status.TRANSCRIBED);
        when(segmentRepo.findByRecordingIdOrderBySeq(recording.getId()))
                .thenReturn(List.of(segment("[00:05] ähm also guten morgen",
                        "[00:05] Guten Morgen.")));

        assertThat(controller.get(recording.getId(), 0).text())
                .contains("Guten Morgen.")
                .doesNotContain("ähm");
    }

    @Test
    void meldetFehlerMitBegruendung() {
        recording.setStatus(Recording.Status.FAILED);
        recording.setDiscardReason("Datei enthaelt kein verwertbares Audio");

        Dtos.TranscriptionView view = controller.get(recording.getId(), 0);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.error()).isEqualTo("Datei enthaelt kein verwertbares Audio");
        assertThat(view.text()).isNull();
    }

    @Test
    void meldetFehlgeschlagenenJobAuchOhneAufnahmefehler() {
        recording.setStatus(Recording.Status.RECORDED);
        ProcessingJob job = ProcessingJob.create(recording.getId(), true);
        job.setStatus(ProcessingJob.Status.FAILED);
        job.setLastError("Whisper nicht erreichbar");
        when(jobRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId())).thenReturn(List.of(job));

        Dtos.TranscriptionView view = controller.get(recording.getId(), 0);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.error()).isEqualTo("Whisper nicht erreichbar");
    }

    private RecordingSegment segment(String transcript, String corrected) {
        RecordingSegment segment = RecordingSegment.create(recording.getId(), 0, "/tmp/a.webm");
        segment.setStatus(RecordingSegment.Status.READY);
        segment.setDurationMs(60_000L);
        segment.setTranscriptText(transcript);
        segment.setCorrectedText(corrected);
        return segment;
    }
}
