package bbbbot.startup;

import bbbbot.config.AppProperties;
import bbbbot.docs.RecordingDocumentService;
import bbbbot.domain.BotSession;
import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingDocumentRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupRecoveryTest {

    private BotSessionRepo sessionRepo;
    private RecordingRepo recordingRepo;
    private RecordingSegmentRepo segmentRepo;
    private ProcessingJobRepo jobRepo;
    private RecordingDocumentRepo documentRepo;
    private RecordingDocumentService documentService;
    private StartupRecovery recovery;

    @BeforeEach
    void setup(@TempDir Path tmp) {
        sessionRepo = mock(BotSessionRepo.class);
        recordingRepo = mock(RecordingRepo.class);
        segmentRepo = mock(RecordingSegmentRepo.class);
        jobRepo = mock(ProcessingJobRepo.class);
        documentRepo = mock(RecordingDocumentRepo.class);
        documentService = mock(RecordingDocumentService.class);

        AppProperties props = mock(AppProperties.class);
        AppProperties.Storage storage = mock(AppProperties.Storage.class);
        when(props.getStorage()).thenReturn(storage);
        when(storage.getRootDir()).thenReturn(tmp.toString());

        // Standard: leere Listen; einzelne Tests ueberschreiben gezielt.
        when(sessionRepo.findByStatusIn(anyList())).thenReturn(List.of());
        when(recordingRepo.findByStatusIn(anyList())).thenReturn(List.of());
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of());
        when(jobRepo.findByStatusOrderByCreatedAt(any())).thenReturn(List.of());
        when(documentRepo.findByStatus(any())).thenReturn(List.of());

        this.tmp = tmp;
        recovery = new StartupRecovery(sessionRepo, recordingRepo, segmentRepo, jobRepo,
                documentRepo, documentService, props);
    }

    private Path tmp;

    /** Aufnahme mit eigenem Verzeichnis, deren Video auf {@code videoStatus} steht. */
    private Recording aufnahmeMitVideoStatus(Recording.VideoStatus videoStatus, Recording.Status status)
            throws java.io.IOException {
        Path dir = java.nio.file.Files.createDirectories(tmp.resolve(UUID.randomUUID().toString()));
        Recording r = Recording.start(UUID.randomUUID(), UUID.randomUUID(), "https://x/y",
                dir.toString(), true, true, false);
        r.setDirectory(dir.toString());
        r.setStatus(status);
        r.setVideoStatus(videoStatus);
        return r;
    }

    @Test
    void verwaisteBotSessionWirdFehlgeschlagen() {
        BotSession s = BotSession.create("https://x/y", "Bot", UUID.randomUUID(), true, false, true, false);
        s.setStatus(BotSession.Status.RECORDING);
        when(sessionRepo.findByStatusIn(anyList())).thenReturn(List.of(s));

        recovery.run(null);

        assertThat(s.getStatus()).isEqualTo(BotSession.Status.FAILED);
        assertThat(s.getEndedAt()).isNotNull();
        assertThat(s.getLastError()).contains("Neustart");
    }

    @Test
    void haengendeAufnahmeUndSegmentWerdenFehlgeschlagen() {
        Recording r = Recording.start(UUID.randomUUID(), UUID.randomUUID(), "https://x/y", "/tmp/x", true, true, false);
        r.setStatus(Recording.Status.FINALIZING);
        when(recordingRepo.findByStatusIn(anyList())).thenReturn(List.of(r));

        RecordingSegment seg = RecordingSegment.create(r.getId(), 0, "/tmp/x/segment_000.webm");
        seg.setStatus(RecordingSegment.Status.TRANSCODING);
        when(segmentRepo.findByRecordingIdOrderBySeq(r.getId())).thenReturn(List.of(seg));

        recovery.run(null);

        assertThat(r.getStatus()).isEqualTo(Recording.Status.FAILED);
        assertThat(r.getVideoStatus()).isEqualTo(Recording.VideoStatus.FAILED);
        assertThat(seg.getStatus()).isEqualTo(RecordingSegment.Status.FAILED);
    }

    @Test
    void laufendeBildschirmaufnahmeBleibtFuerDieRettungStehen() {
        // Ihre Rohdaten liegen auf der Platte; CaptureService.sweepStale() schliesst
        // sie kurz nach dem Start ab. Auf FAILED zu setzen wuerde sie wegwerfen.
        Recording capture = Recording.start(null, UUID.randomUUID(), null, "/tmp/x", true, true, false);
        capture.setSource(Recording.Source.CAPTURE);
        capture.setStatus(Recording.Status.RECORDING);
        when(recordingRepo.findByStatusIn(anyList())).thenReturn(List.of(capture));

        recovery.run(null);

        assertThat(capture.getStatus()).isEqualTo(Recording.Status.RECORDING);
        assertThat(capture.getDiscardReason()).isNull();
    }

    @Test
    void abgebrocheneBildschirmaufnahmeImAbschlussWirdFehlgeschlagen() {
        // FINALIZING heisst: Die Rohdatei war schon in der Verarbeitung, deren
        // Threads der Neustart zerrissen hat - hier gibt es nichts mehr zu retten.
        Recording capture = Recording.start(null, UUID.randomUUID(), null, "/tmp/x", true, true, false);
        capture.setSource(Recording.Source.CAPTURE);
        capture.setStatus(Recording.Status.FINALIZING);
        when(recordingRepo.findByStatusIn(anyList())).thenReturn(List.of(capture));

        recovery.run(null);

        assertThat(capture.getStatus()).isEqualTo(Recording.Status.FAILED);
    }

    @Test
    void fertigesVideoEinerAusgewertetenAufnahmeWirdNachtraeglichEingehaengt() throws Exception {
        // Der Mux war fertig, der Neustart kam vor dem Statuswechsel: Die MP4 liegt
        // da, die Anzeige haengt trotzdem auf "wird verarbeitet".
        Recording r = aufnahmeMitVideoStatus(Recording.VideoStatus.MUXING, Recording.Status.DONE);
        Path mp4 = java.nio.file.Path.of(r.getDirectory()).resolve("meeting.mp4");
        java.nio.file.Files.writeString(mp4, "video");
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of(r));

        recovery.run(null);

        assertThat(r.getVideoStatus()).isEqualTo(Recording.VideoStatus.READY);
        assertThat(r.getVideoPath()).isEqualTo(mp4.toAbsolutePath().toString());
        assertThat(r.getStatus()).isEqualTo(Recording.Status.DONE);
    }

    @Test
    void haengendesVideoOhneDateiWirdFehlgeschlagen() throws Exception {
        // Die Mux-Warteschlange lebt nur im Speicher - nach dem Neustart kommt zu
        // dieser Aufnahme nie mehr ein Video. Lieber ehrlich FAILED als endlos
        // "wird verarbeitet".
        Recording r = aufnahmeMitVideoStatus(Recording.VideoStatus.MUXING, Recording.Status.DONE);
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of(r));

        recovery.run(null);

        assertThat(r.getVideoStatus()).isEqualTo(Recording.VideoStatus.FAILED);
    }

    @Test
    void videoEinerLaufendenBildschirmaufnahmeBleibtUnberuehrt() throws Exception {
        Recording capture = aufnahmeMitVideoStatus(Recording.VideoStatus.RECORDING, Recording.Status.RECORDING);
        capture.setSource(Recording.Source.CAPTURE);
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of(capture));

        recovery.run(null);

        assertThat(capture.getVideoStatus()).isEqualTo(Recording.VideoStatus.RECORDING);
    }

    @Test
    void laufenderJobWirdNeuEingereihtUndAufnahmeZurueckgesetzt() {
        ProcessingJob job = ProcessingJob.create(UUID.randomUUID(), false);
        job.setStatus(ProcessingJob.Status.RUNNING);
        when(jobRepo.findByStatusOrderByCreatedAt(ProcessingJob.Status.RUNNING)).thenReturn(List.of(job));

        Recording r = Recording.start(null, UUID.randomUUID(), null, "/tmp/x", false, true, false);
        r.setStatus(Recording.Status.PROCESSING);
        when(recordingRepo.findById(job.getRecordingId())).thenReturn(Optional.of(r));

        recovery.run(null);

        assertThat(job.getStatus()).isEqualTo(ProcessingJob.Status.PENDING);
        assertThat(job.getStartedAt()).isNull();
        assertThat(r.getStatus()).isEqualTo(Recording.Status.RECORDED);
    }
}
