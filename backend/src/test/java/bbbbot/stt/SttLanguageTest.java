package bbbbot.stt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SttLanguageTest {

    @Test
    void leereAngabeBedeutetAdminStandard() {
        assertThat(SttLanguage.normalize(null)).isNull();
        assertThat(SttLanguage.normalize("   ")).isNull();
    }

    @Test
    void normalisiertCodeUndAutomatik() {
        assertThat(SttLanguage.normalize(" EN ")).isEqualTo("en");
        assertThat(SttLanguage.normalize("pt-BR")).isEqualTo("pt-br");
        assertThat(SttLanguage.normalize("Auto")).isEqualTo(SttLanguage.AUTO);
    }

    @Test
    void weistUnsinnAb() {
        assertThatThrownBy(() -> SttLanguage.normalize("deutsch bitte"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SttLanguage.normalize("de&task=translate"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
