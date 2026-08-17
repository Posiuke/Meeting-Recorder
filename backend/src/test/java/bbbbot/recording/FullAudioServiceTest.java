package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.media.FfmpegService;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Zusammenfuegen der Tonspur: Wiederverwendung, Neuaufbau und Grenzfaelle. */
class FullAudioServiceTest {

    @TempDir
    Path dir;

    private RecordingSegmentRepo segmentRepo;
    private FfmpegService ffmpeg;
    private FullAudioService service;
    private Recording recording;

    @BeforeEach
    void setUp() {
        segmentRepo = mock(RecordingSegmentRepo.class);
        ffmpeg = mock(FfmpegService.class);
        service = new FullAudioService(segmentRepo, ffmpeg);

        recording = Recording.start(null, UUID.randomUUID(), null, dir.toString(), false, true, false);
        // ffmpeg wird nicht wirklich aufgerufen - die Zieldatei entsteht hier
        when(ffmpeg.concatMp3(anyList(), any())).thenAnswer(invocation -> {
            Path out = invocation.getArgument(1, Path.class);
            Files.writeString(out, "zusammengefuegt", StandardCharsets.UTF_8);
            return new FfmpegService.TranscodeResult(true, out, 1000L, null);
        });
    }

    @Test
    void ohneSegmenteGibtEsKeineTonspur() {
        segments();

        assertThat(service.fullAudio(recording)).isEmpty();
        assertThat(service.hasAudio(recording)).isFalse();
        verify(ffmpeg, never()).concatMp3(anyList(), any());
    }

    @Test
    void einzelnesSegmentIstSchonDieGanzeAufnahme() throws IOException {
        Path only = segmentFile("0.mp3");
        segments(only);

        assertThat(service.fullAudio(recording)).contains(only);
        verify(ffmpeg, never()).concatMp3(anyList(), any());
    }

    @Test
    void mehrereSegmenteWerdenZusammengefuegtUndWiederverwendet() throws IOException {
        segments(segmentFile("0.mp3"), segmentFile("1.mp3"));

        Path first = service.fullAudio(recording).orElseThrow();
        assertThat(first).isEqualTo(dir.resolve("audio.mp3")).exists();

        // Zweiter Abruf nimmt die vorhandene Datei - kein zweiter ffmpeg-Lauf
        assertThat(service.fullAudio(recording)).contains(first);
        verify(ffmpeg, times(1)).concatMp3(anyList(), any());
    }

    @Test
    void neueresSegmentErzwingtNeuaufbau() throws IOException {
        Path zero = segmentFile("0.mp3");
        segments(zero, segmentFile("1.mp3"));
        service.fullAudio(recording);

        // Erneute Transkription: Ein Segment ist juenger als die fertige Datei
        Files.setLastModifiedTime(zero, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        service.fullAudio(recording);
        verify(ffmpeg, times(2)).concatMp3(anyList(), any());
    }

    @Test
    void gescheitertesZusammenfuegenLiefertNichts() throws IOException {
        segments(segmentFile("0.mp3"), segmentFile("1.mp3"));
        // doReturn statt when(...): Sonst liefe beim Umstellen noch einmal die
        // Antwort aus setUp - mit null-Argumenten.
        doReturn(new FfmpegService.TranscodeResult(false, null, null, "ffmpeg exit=1"))
                .when(ffmpeg).concatMp3(anyList(), any());

        assertThat(service.fullAudio(recording)).isEmpty();
    }

    @Test
    void nichtFertigeSegmenteBleibenAussenVor() throws IOException {
        RecordingSegment ready = segment(0, segmentFile("0.mp3"), RecordingSegment.Status.READY);
        RecordingSegment empty = segment(1, segmentFile("1.mp3"), RecordingSegment.Status.EMPTY);
        when(segmentRepo.findByRecordingIdOrderBySeq(recording.getId())).thenReturn(List.of(ready, empty));

        // Nur ein verwertbares Segment - also kein Zusammenfuegen noetig
        assertThat(service.fullAudio(recording)).contains(Path.of(ready.getMp3Path()));
        verify(ffmpeg, never()).concatMp3(anyList(), any());
    }

    private Path segmentFile(String name) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, name, StandardCharsets.UTF_8);
        return file;
    }

    private void segments(Path... mp3s) {
        List<RecordingSegment> list = new ArrayList<>();
        for (int i = 0; i < mp3s.length; i++) {
            list.add(segment(i, mp3s[i], RecordingSegment.Status.READY));
        }
        when(segmentRepo.findByRecordingIdOrderBySeq(recording.getId())).thenReturn(list);
    }

    private RecordingSegment segment(int seq, Path mp3, RecordingSegment.Status status) {
        RecordingSegment segment = RecordingSegment.create(recording.getId(), seq, mp3 + ".webm");
        segment.setMp3Path(mp3.toString());
        segment.setStatus(status);
        return segment;
    }
}
