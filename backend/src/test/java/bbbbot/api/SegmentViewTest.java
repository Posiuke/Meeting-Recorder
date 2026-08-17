package bbbbot.api;

import bbbbot.domain.RecordingSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code hasAudio} steuert im Frontend die Wiedergabe (Player, Sprung aus dem
 * Transkript). Es darf deshalb nicht schon dann true sein, wenn nur ein Pfad in
 * der Datenbank steht - bei alten Aufnahmen kann die Datei laengst weg sein.
 */
class SegmentViewTest {

    @TempDir
    Path dir;

    @Test
    void vorhandeneDateiGiltAlsAbspielbar() throws IOException {
        Path mp3 = Files.writeString(dir.resolve("0.mp3"), "audio", StandardCharsets.UTF_8);

        assertThat(Dtos.SegmentView.of(segment(mp3.toString())).hasAudio()).isTrue();
    }

    @Test
    void fehlendeDateiGiltNichtAlsAbspielbar() {
        String missing = dir.resolve("weg.mp3").toString();

        assertThat(Dtos.SegmentView.of(segment(missing)).hasAudio()).isFalse();
    }

    @Test
    void ohnePfadGibtEsKeinAudio() {
        assertThat(Dtos.SegmentView.of(segment(null)).hasAudio()).isFalse();
        assertThat(Dtos.SegmentView.of(segment("  ")).hasAudio()).isFalse();
    }

    private static RecordingSegment segment(String mp3Path) {
        RecordingSegment segment = RecordingSegment.create(UUID.randomUUID(), 0, "/tmp/x.webm");
        segment.setStatus(RecordingSegment.Status.READY);
        segment.setMp3Path(mp3Path);
        return segment;
    }
}
