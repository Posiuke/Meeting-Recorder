package bbbbot.stt;

import bbbbot.domain.RecordingSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptAssemblerTest {

    private static RecordingSegment segment(int seq, Long durationMs, String transcript) {
        RecordingSegment s = RecordingSegment.create(UUID.randomUUID(), seq, "seg_" + seq + ".webm");
        s.setStatus(RecordingSegment.Status.READY);
        s.setDurationMs(durationMs);
        s.setTranscriptText(transcript);
        return s;
    }

    @Test
    void verschiebtZeitstempelUmDauerDerVorherigenSegmente() {
        // Zwei 10-Minuten-Segmente: Whisper beginnt in jedem wieder bei [00:00]
        var segments = List.of(
                segment(0, 600_000L, "[00:00] Hallo zusammen.\n[09:58] Ende Segment eins."),
                segment(1, 600_000L, "[00:05] Weiter im zweiten Segment."));

        var entries = TranscriptAssembler.assemble(segments);

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).startSeconds()).isEqualTo(0);
        assertThat(entries.get(1).startSeconds()).isEqualTo(598);
        // 600s Offset aus Segment 1 + 5s im Segment
        assertThat(entries.get(2).startSeconds()).isEqualTo(605);
        assertThat(entries.get(2).text()).isEqualTo("Weiter im zweiten Segment.");
    }

    @Test
    void uebernimmtSprecherLabelsAusDiarisierung() {
        var segments = List.of(segment(0, 600_000L, """
                SPEAKER_00:
                [00:01] Guten Morgen.
                [00:04] Fangen wir an.
                SPEAKER_01:
                [00:10] Eine Frage bitte.
                """));

        var entries = TranscriptAssembler.assemble(segments);

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).speaker()).isEqualTo("SPEAKER_00");
        assertThat(entries.get(1).speaker()).isEqualTo("SPEAKER_00");
        assertThat(entries.get(2).speaker()).isEqualTo("SPEAKER_01");
    }

    @Test
    void behandeltTextOhneZeitstempelAlsEinenEintrag() {
        // output=text liefert nur Fliesstext ohne [mm:ss]-Marker
        var segments = List.of(
                segment(0, 600_000L, "Erster Block Zeile eins.\nZeile zwei."),
                segment(1, 600_000L, "Zweiter Block."));

        var entries = TranscriptAssembler.assemble(segments);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).startSeconds()).isEqualTo(0);
        assertThat(entries.get(0).text()).isEqualTo("Erster Block Zeile eins. Zeile zwei.");
        assertThat(entries.get(1).startSeconds()).isEqualTo(600);
    }

    @Test
    void ueberspringtSegmenteOhneTranskriptBehaeltAberDerenDauer() {
        var segments = List.of(
                segment(0, 600_000L, null),
                segment(1, 600_000L, "[00:02] Erst hier gesprochen."));

        var entries = TranscriptAssembler.assemble(segments);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).startSeconds()).isEqualTo(602);
    }

    @Test
    void ersetztSprecherLabelsDurchTeilnehmernamen() {
        var entries = List.of(
                new TranscriptAssembler.Entry(0, "SPEAKER_00", "Guten Morgen."),
                new TranscriptAssembler.Entry(5, "SPEAKER_01", "Hallo."),
                new TranscriptAssembler.Entry(9, null, "Ohne Sprecher."));

        var mapped = TranscriptAssembler.applyNames(entries,
                java.util.Map.of("SPEAKER_00", "Anna Beispiel"));

        assertThat(mapped.get(0).speaker()).isEqualTo("Anna Beispiel");
        // Label ohne Zuordnung und Eintraege ohne Sprecher bleiben unveraendert
        assertThat(mapped.get(1).speaker()).isEqualTo("SPEAKER_01");
        assertThat(mapped.get(2).speaker()).isNull();
        assertThat(mapped.get(0).startSeconds()).isEqualTo(0);
        assertThat(mapped.get(0).text()).isEqualTo("Guten Morgen.");
    }

    @Test
    void rendertTextMitStundenformatUndSprecher() {
        var entries = List.of(
                new TranscriptAssembler.Entry(59, "SPEAKER_00", "Kurz vor der Minute."),
                new TranscriptAssembler.Entry(3725, null, "Nach einer Stunde."));

        String text = TranscriptAssembler.toText(entries);

        assertThat(text).isEqualTo("[00:59] SPEAKER_00: Kurz vor der Minute.\n[1:02:05] Nach einer Stunde.");
    }
}
