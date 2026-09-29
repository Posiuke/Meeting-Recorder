package bbbbot.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Die Pruefregeln beim nachtraeglichen Umbenennen einer Aufnahme. */
class RecordingTitleTest {

    @Test
    void entferntLeerzeichenAmRand() {
        assertThat(RecordingController.requireTitle("  Jour fixe KW 40  ")).isEqualTo("Jour fixe KW 40");
    }

    @Test
    void lehntLeereNamenAb() {
        for (String raw : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> RecordingController.requireTitle(raw))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void erlaubtGenauSoVieleZeichenWieDieSpalte() {
        String max = "a".repeat(RecordingController.MAX_TITLE_LENGTH);
        assertThat(RecordingController.requireTitle(max)).isEqualTo(max);
        assertThatThrownBy(() -> RecordingController.requireTitle(max + "a"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
