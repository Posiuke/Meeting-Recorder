package bbbbot.recording;

import bbbbot.stt.TranscriptAssembler.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TalkLogMatcherTest {

    @Test
    void ordnetLabelDerPersonMitDerMeistenUeberlappungZu() {
        List<Entry> entries = List.of(
                new Entry(0, "SPEAKER_00", "Hallo zusammen."),
                new Entry(20, "SPEAKER_01", "Danke fuer die Einladung."),
                new Entry(45, "SPEAKER_00", "Gern."),
                new Entry(70, "SPEAKER_01", "Ende."));
        var intervals = TalkLogMatcher.parse("""
                0.5\t19.0\tAnna
                21.0\t44.0\tTom
                45.5\t69.0\tAnna
                70.5\t95.0\tTom
                """);

        var matches = TalkLogMatcher.match(entries, intervals);

        assertThat(matches.get("SPEAKER_00").name()).isEqualTo("Anna");
        assertThat(matches.get("SPEAKER_00").confidence()).isEqualTo("HIGH");
        assertThat(matches.get("SPEAKER_01").name()).isEqualTo("Tom");
        assertThat(matches.get("SPEAKER_01").evidence()).contains("BBB-Sprechanzeige").contains("Tom");
    }

    @Test
    void keinVorschlagWennZweiPersonenGleichvielMitreden() {
        List<Entry> entries = List.of(new Entry(0, "SPEAKER_00", "Durcheinander."));
        var intervals = TalkLogMatcher.parse("0\t30\tAnna\n0\t30\tTom\n");

        assertThat(TalkLogMatcher.match(entries, intervals)).isEmpty();
    }

    @Test
    void zuWenigUeberlappungErgibtKeinenVorschlag() {
        List<Entry> entries = List.of(new Entry(0, "SPEAKER_00", "Kurz."), new Entry(3, "SPEAKER_01", "Auch."));
        var intervals = TalkLogMatcher.parse("0\t2\tAnna\n");

        assertThat(TalkLogMatcher.match(entries, intervals)).isEmpty();
    }

    @Test
    void kaputteProtokollzeilenWerdenUebersprungen() {
        assertThat(TalkLogMatcher.parse("x\ty\tAnna\n5\t3\tTom\n1\t2\t\n1\t2\tEva\n"))
                .containsExactly(new TalkLogMatcher.Interval(1, 2, "Eva"));
    }
}
