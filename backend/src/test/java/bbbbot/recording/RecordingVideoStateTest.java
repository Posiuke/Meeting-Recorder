package bbbbot.recording;

import bbbbot.config.AppProperties;
import bbbbot.domain.BotSession;
import bbbbot.domain.Recording;
import bbbbot.media.FfmpegService;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sicherheitsnetz gegen Aufnahmen, deren Video-Anzeige dauerhaft auf
 * "wird verarbeitet" stehen bleibt.
 */
class RecordingVideoStateTest {

    @TempDir
    Path storageRoot;

    private RecordingRepo recordingRepo;
    private BotSessionRepo sessionRepo;
    private RecordingService service;

    @BeforeEach
    void setup() {
        recordingRepo = mock(RecordingRepo.class);
        sessionRepo = mock(BotSessionRepo.class);
        SettingsService settings = mock(SettingsService.class);
        FfmpegService ffmpeg = mock(FfmpegService.class);

        AppProperties props = new AppProperties();
        props.getStorage().setRootDir(storageRoot.toString());

        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of());

        service = new RecordingService(props, ffmpeg, settings, recordingRepo,
                mock(RecordingSegmentRepo.class), mock(ProcessingJobRepo.class), sessionRepo);
    }

    /** Aufnahme mit eigenem Verzeichnis, die vor {@code endeVorMinuten} beendet wurde. */
    private Recording aufnahme(Recording.VideoStatus videoStatus, long endeVorMinuten) throws IOException {
        Path dir = Files.createDirectories(storageRoot.resolve(UUID.randomUUID().toString()));
        Recording r = Recording.start(UUID.randomUUID(), UUID.randomUUID(), "https://x/y",
                dir.toString(), true, true, false);
        r.setDirectory(dir.toString());
        r.setStatus(Recording.Status.DONE);
        r.setVideoStatus(videoStatus);
        r.setEndedAt(Instant.now().minus(endeVorMinuten, ChronoUnit.MINUTES));
        when(recordingRepo.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void fertigeMp4WirdNachtraeglichEingehaengt() throws IOException {
        Recording r = aufnahme(Recording.VideoStatus.MUXING, 240);
        Path mp4 = Path.of(r.getDirectory()).resolve("meeting.mp4");
        Files.writeString(mp4, "video");
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of(r));

        service.sweepStuckVideos();

        verify(recordingRepo).updateVideoState(r.getId(), Recording.VideoStatus.READY,
                mp4.toAbsolutePath().toString());
    }

    @Test
    void haengendesVideoOhneDateiWirdFehlgeschlagen() throws IOException {
        Recording r = aufnahme(Recording.VideoStatus.MUXING, 240);
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of(r));

        service.sweepStuckVideos();

        verify(recordingRepo).updateVideoState(eq(r.getId()), eq(Recording.VideoStatus.FAILED), isNull());
    }

    @Test
    void frischBeendeteAufnahmeBleibtInDerSchonfrist() throws IOException {
        // Das Muxen darf dauern - erst nach der Schonfrist gilt es als haengend.
        Recording r = aufnahme(Recording.VideoStatus.MUXING, 5);
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of(r));

        service.sweepStuckVideos();

        verify(recordingRepo, never()).updateVideoState(any(), any(), any());
    }

    @Test
    void laufendeBotSitzungWirdNichtVorzeitigAbgeschrieben() throws IOException {
        // Playwright liefert das Video erst beim Schliessen des Browser-Kontextes.
        // Eine frueh gestoppte Aufnahme wartet also bis zum Ende der Sitzung.
        Recording r = aufnahme(Recording.VideoStatus.RECORDING, 240);
        BotSession session = BotSession.create("https://x/y", "Bot", UUID.randomUUID(), true, true, true, false);
        session.setStatus(BotSession.Status.RECORDING);
        when(sessionRepo.findById(r.getBotSessionId())).thenReturn(Optional.of(session));
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of(r));

        service.sweepStuckVideos();

        verify(recordingRepo, never()).updateVideoState(any(), any(), any());
    }

    @Test
    void beendeteBotSitzungLaesstDasVideoAbschreiben() throws IOException {
        Recording r = aufnahme(Recording.VideoStatus.RECORDING, 240);
        BotSession session = BotSession.create("https://x/y", "Bot", UUID.randomUUID(), true, true, true, false);
        session.setStatus(BotSession.Status.STOPPED);
        when(sessionRepo.findById(r.getBotSessionId())).thenReturn(Optional.of(session));
        when(recordingRepo.findByVideoStatusIn(anyList())).thenReturn(List.of(r));

        service.sweepStuckVideos();

        verify(recordingRepo).updateVideoState(eq(r.getId()), eq(Recording.VideoStatus.FAILED), isNull());
    }

    @Test
    void ohneVideoHandleWartendeAufnahmenWerdenFehlgeschlagen() throws IOException {
        // Der Browser-Kontext hat keine Videodatei hinterlassen: Zu diesen
        // Aufnahmen kommt nie ein Video.
        Recording wartend = aufnahme(Recording.VideoStatus.RECORDING, 1);

        service.markVideoUnavailable(List.of(wartend.getId()));

        verify(recordingRepo).updateVideoState(eq(wartend.getId()), eq(Recording.VideoStatus.FAILED), isNull());
    }

    @Test
    void fertigesVideoWirdNichtNachtraeglichVerworfen() throws IOException {
        Recording fertig = aufnahme(Recording.VideoStatus.READY, 1);

        service.markVideoUnavailable(List.of(fertig.getId()));

        verify(recordingRepo, never()).updateVideoState(any(), any(), any());
    }
}
