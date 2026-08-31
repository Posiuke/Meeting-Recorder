package bbbbot.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deutung der Sortierangaben aus der Anfrage. */
class RecordingSortTest {

    /**
     * Ohne Angabe: neueste Aufnahme zuerst. Beim Titel ist aufsteigend die
     * erwartete Leserichtung (A vor Z), beim Datum absteigend (neu vor alt).
     */
    @Test
    void vorgabenProSortierschluessel() {
        assertThat(RecordingSort.of(null, null))
                .returns(RecordingSort.BY_DATE, RecordingSort::key)
                .returns(false, RecordingSort::ascending);
        assertThat(RecordingSort.of("  ", "")).isEqualTo(RecordingSort.DEFAULT);
        assertThat(RecordingSort.of("title", null))
                .returns(RecordingSort.BY_TITLE, RecordingSort::key)
                .returns(true, RecordingSort::ascending);
    }

    @Test
    void richtungLaesstSichUmdrehen() {
        assertThat(RecordingSort.of("DATE", "asc").ascending()).isTrue();
        assertThat(RecordingSort.of("Title", "DESC").ascending()).isFalse();
    }

    @Test
    void byTitleUnterscheidetDieBeidenSchluessel() {
        assertThat(RecordingSort.of("title", null).byTitle()).isTrue();
        assertThat(RecordingSort.of("date", null).byTitle()).isFalse();
    }

    @Test
    void weistUnbekannteAngabenAb() {
        assertThatThrownBy(() -> RecordingSort.of("dauer", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unbekannte Sortierung");
        assertThatThrownBy(() -> RecordingSort.of("date", "aufwaerts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unbekannte Sortierrichtung");
    }
}
