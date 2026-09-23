package bbbbot.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Adresse der Anwendung aus der Browser-Anfrage - wie window.location.origin. */
class RequestOriginTest {

    @Test
    void originHeaderGewinnt() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("Origin", "https://recorder.intern");
        r.addHeader("Referer", "https://anders.intern/bots");
        assertThat(RequestOrigin.of(r)).isEqualTo("https://recorder.intern");
    }

    @Test
    void refererOhnePfadAlsRueckfall() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("Referer", "http://192.168.178.43:8090/bots?x=1");
        assertThat(RequestOrigin.of(r)).isEqualTo("http://192.168.178.43:8090");
    }

    /** Ohne Browser-Header (z.B. curl mit API-Key): die Adresse der Anfrage selbst. */
    @Test
    void anfrageAdresseZuletzt() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/bots");
        r.setScheme("https");
        r.setServerName("recorder.intern");
        r.setServerPort(443);
        assertThat(RequestOrigin.of(r)).isEqualTo("https://recorder.intern");
        r.setServerPort(8443);
        assertThat(RequestOrigin.of(r)).isEqualTo("https://recorder.intern:8443");
    }

    @Test
    void unbrauchbareHeaderWerdenIgnoriert() {
        assertThat(RequestOrigin.normalize("null")).isNull();
        assertThat(RequestOrigin.normalize("javascript:alert(1)")).isNull();
        assertThat(RequestOrigin.normalize("file:///etc/passwd")).isNull();
        assertThat(RequestOrigin.normalize("kein url")).isNull();
    }
}
