package bbbbot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMarkersTest {

    @Test
    void generiertEindeutigeMarkerMitPraefix() {
        String a = SessionMarkers.generate();
        String b = SessionMarkers.generate();
        assertTrue(a.startsWith("REC"));
        assertEquals(15, a.length());
        assertNotEquals(a, b);
    }

    @Test
    void zweiMarkerSystemVerwaltetActiveUndCutoffGetrennt() {
        SessionMarkers markers = new SessionMarkers();
        assertFalse(markers.hasActive());
        assertFalse(markers.hasCutoff());

        markers.setActive("RECactive123");
        markers.setCutoff("RECcutoff456");
        assertTrue(markers.hasActive());
        assertTrue(markers.hasCutoff());
        assertEquals("RECactive123", markers.getActive());
        assertEquals("RECcutoff456", markers.getCutoff());

        markers.clearActive();
        assertFalse(markers.hasActive());
        assertTrue(markers.hasCutoff());
    }
}
