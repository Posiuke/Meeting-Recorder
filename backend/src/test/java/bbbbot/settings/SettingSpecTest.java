package bbbbot.settings;

import bbbbot.settings.SettingsService.SettingSpec;
import bbbbot.settings.SettingsService.SettingType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingSpecTest {

    @Test
    void jedeEinstellungHatEinenTypUndDerStandardwertIstGueltig() {
        SettingsService.schema().forEach((key, spec) ->
                assertThat(SettingsService.problemWith(spec, SettingsService.defaults().get(key).trim()))
                        .as(key).isNull());
    }

    @Test
    void zahlenMitGrenzen() {
        SettingSpec spec = SettingsService.schema().get(SettingsService.WHISPER_RETRY_ATTEMPTS);
        assertThat(spec.type()).isEqualTo(SettingType.INTEGER);
        assertThat(SettingsService.problemWith(spec, "3")).isNull();
        assertThat(SettingsService.problemWith(spec, "0")).contains("Minimum 1");
        assertThat(SettingsService.problemWith(spec, "abc")).contains("ganze Zahl");
        assertThat(SettingsService.problemWith(spec, "1.5")).contains("ganze Zahl");
        assertThat(SettingsService.problemWith(spec, "")).contains("leer");
    }

    @Test
    void jaNeinAuswahlUndOptionaleAdresse() {
        var schema = SettingsService.schema();
        assertThat(SettingsService.problemWith(schema.get(SettingsService.SPEAKERS_AUTO_APPLY), "ja")).isNotNull();
        assertThat(SettingsService.problemWith(schema.get(SettingsService.RECORDING_MP3_BITRATE), "128k")).isNull();
        assertThat(SettingsService.problemWith(schema.get(SettingsService.RECORDING_MP3_BITRATE), "100k")).isNotNull();
        assertThat(SettingsService.problemWith(schema.get(SettingsService.DOCUMENTS_TIKA_URL), "")).isNull();
        assertThat(SettingsService.problemWith(schema.get(SettingsService.WHISPER_URL), "")).isNotNull();
        assertThat(SettingsService.problemWith(schema.get(SettingsService.WHISPER_URL), "ftp://x")).isNotNull();
        assertThat(SettingsService.problemWith(schema.get(SettingsService.LLM_TEMPERATURE), "2.5")).contains("Maximum 2");
    }
}
