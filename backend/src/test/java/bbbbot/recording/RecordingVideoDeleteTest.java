package bbbbot.recording;

import bbbbot.config.AppProperties;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nachtraegliches Entfernen des Videos: nur das Bild geht, die Tonspur bleibt. */
class RecordingVideoDeleteTest {

    @TempDir
    Path storageRoot;

    private RecordingRepo recordingRepo;
    private RecordingSegmentRepo segmentRepo;
    private FfmpegService ffmpeg;
    private RecordingService service;

    @BeforeEach
    void setup() {
        recordingRepo = mock(RecordingRepo.class);
        segmentRepo = mock(RecordingSegmentRepo.class);
        ffmpeg = mock(FfmpegService.class);

        AppProperties props = new AppProperties();
        props.getStorage().setRootDir(storageRoot.toString());

        service = new RecordingService(props, ffmpeg, mock(SettingsService.class), recordingRepo,
                segmentRepo, mock(ProcessingJobRepo.class), mock(BotSessionRepo.class),
                mock(bbbbot.sharing.BotTemplateAccess.class));
    }

    private Recording aufnahme(Recording.Source source, Recording.VideoStatus videoStatus) throws IOException {
        Path dir = Files.createDirectories(storageRoot.resolve(UUID.randomUUID().toString()));
        Recording r = Recording.start(null, UUID.randomUUID(), null, dir.toString(), true, true, false);
        r.setDirectory(dir.toString());
        r.setSource(source);
        r.setStatus(Recording.Status.DONE);
        r.setVideoStatus(videoStatus);
        when(recordingRepo.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private RecordingSegment segment(Recording r, Path source, Path mp3) {
        RecordingSegment s = RecordingSegment.create(r.getId(), 0, source.toAbsolutePath().toString());
        s.setMp3Path(mp3.toAbsolutePath().toString());
        s.setStatus(RecordingSegment.Status.READY);
        return s;
    }

    @Test
    void botAufnahmeVerliertNurDasMp4() throws IOException {
        Recording r = aufnahme(Recording.Source.BOT, Recording.VideoStatus.READY);
        Path dir = Path.of(r.getDirectory());
        Path mp4 = Files.writeString(dir.resolve("meeting.mp4"), "video");
        Path webm = Files.writeString(dir.resolve("segment_000.webm"), "ton");
        Path mp3 = Files.writeString(dir.resolve("segment_000.mp3"), "ton");
        r.setVideoPath(mp4.toString());
        when(segmentRepo.findByRecordingIdOrderBySeq(r.getId())).thenReturn(List.of(segment(r, webm, mp3)));

        assertThat(service.deleteVideo(r.getId())).isTrue();

        assertThat(mp4).doesNotExist();
        assertThat(webm).exists();
        assertThat(mp3).exists();
        verify(recordingRepo).updateVideoState(r.getId(), Recording.VideoStatus.DELETED, null);
    }

    @Test
    void uploadVerliertAuchDieQuelldateiMitBild() throws IOException {
        Recording r = aufnahme(Recording.Source.UPLOAD, Recording.VideoStatus.READY);
        Path dir = Path.of(r.getDirectory());
        Path mp4 = Files.writeString(dir.resolve("meeting.mp4"), "video");
        Path upload = Files.writeString(dir.resolve("upload_meeting.mkv"), "video+ton");
        Path mp3 = Files.writeString(dir.resolve("upload_meeting.mkv.mp3"), "ton");
        r.setVideoPath(mp4.toString());
        when(segmentRepo.findByRecordingIdOrderBySeq(r.getId())).thenReturn(List.of(segment(r, upload, mp3)));
        when(ffmpeg.videoStreamCodec(any())).thenReturn("h264");

        assertThat(service.deleteVideo(r.getId())).isTrue();

        assertThat(mp4).doesNotExist();
        assertThat(upload).doesNotExist();
        assertThat(mp3).exists();
    }

    @Test
    void waehrendDesMuxensWirdNichtsGeloescht() throws IOException {
        Recording r = aufnahme(Recording.Source.BOT, Recording.VideoStatus.MUXING);
        Path mp4 = Files.writeString(Path.of(r.getDirectory()).resolve("meeting.mp4"), "halb fertig");

        assertThat(service.deleteVideo(r.getId())).isFalse();

        assertThat(mp4).exists();
        verify(recordingRepo, never()).updateVideoState(eq(r.getId()), any(), isNull());
    }
}
