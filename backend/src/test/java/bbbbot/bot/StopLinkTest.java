package bbbbot.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Anonymer Stopp-Link: Token, Hash und Einbau in den Aufnahme-Hinweis. */
class StopLinkTest {

    private static BotConfig config(String warnMessage, boolean enabled, String publicUrl) {
        return new BotConfig("STARTRECORDING", "STOPRECORDING", true, warnMessage, 1, 5000,
                true, 3, 1000, 2.0, 10, 1000, enabled, publicUrl);
    }

    @Test
    void tokensSindZufaelligUndUrlTauglich() {
        String a = StopTokens.generate();
        String b = StopTokens.generate();
        assertThat(a).isNotEqualTo(b).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(StopTokens.hash(a)).hasSize(64).isEqualTo(StopTokens.hash(a));
        assertThat(StopTokens.matches(StopTokens.hash(a), StopTokens.hash(b))).isFalse();
    }

    /** Pfad-Eingaben von aussen: Unsinn fuehrt nicht zu einem Treffer. */
    @Test
    void unpassendeEingabenErgebenKeinenHash() {
        assertThat(StopTokens.hash(null)).isNull();
        assertThat(StopTokens.hash("")).isNull();
        assertThat(StopTokens.hash("../../etc")).isNull();
        assertThat(StopTokens.hash("x".repeat(StopTokens.MAX_LENGTH + 1))).isNull();
        assertThat(StopTokens.matches(null, null)).isFalse();
    }

    @Test
    void linkNurWennEingeschaltetUndAdresseGesetzt() {
        assertThat(config("x", false, "https://rec.intern").stopUrlBase()).isNull();
        assertThat(config("x", true, "  ").stopUrlBase()).isNull();
        assertThat(config("x", true, "https://rec.intern/").stopUrlBase()).isEqualTo("https://rec.intern");
    }

    @Test
    void linkErsetztDenPlatzhalter() {
        BotConfig c = config("Aufnahme laeuft. ${STOP} oder anonym: ${STOP_URL}", true, "https://rec.intern");
        assertThat(c.buildWarnMessage("RECabc", "https://rec.intern/stop/T"))
                .isEqualTo("Aufnahme laeuft. STOPRECORDING oder anonym: https://rec.intern/stop/T [RECabc]");
    }

    /** Eine frueher angepasste Meldung ohne Platzhalter verliert den Link nicht. */
    @Test
    void ohnePlatzhalterWirdDerLinkAngehaengt() {
        BotConfig c = config("Aufnahme laeuft. Verhindern mit ${STOP}", true, "https://rec.intern");
        assertThat(c.buildWarnMessage("RECabc", "https://rec.intern/stop/T"))
                .isEqualTo("Aufnahme laeuft. Verhindern mit STOPRECORDING"
                        + " Ohne Chat-Nachricht beenden und verwerfen: https://rec.intern/stop/T [RECabc]");
    }

    @Test
    void ohneLinkVerschwindetDerPlatzhalter() {
        BotConfig c = config("Aufnahme laeuft. ${STOP_URL} Verhindern mit ${STOP}", false, "");
        assertThat(c.buildWarnMessage("RECabc", null))
                .isEqualTo("Aufnahme laeuft. Verhindern mit STOPRECORDING [RECabc]");
        // Mehrzeilige Meldungen ohne Platzhalter bleiben unangetastet.
        assertThat(config("Zeile 1\nZeile 2", false, "").buildWarnMessage("RECabc"))
                .isEqualTo("Zeile 1\nZeile 2 [RECabc]");
    }
}
