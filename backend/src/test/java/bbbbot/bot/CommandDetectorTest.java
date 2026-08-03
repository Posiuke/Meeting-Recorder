package bbbbot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Portierte Testfaelle der alten Chat-Befehls-Erkennung (27 Tests im Original). */
class CommandDetectorTest {

    @Test
    void findetBefehlMitWortgrenzen() {
        assertTrue(CommandDetector.containsCommand("bitte STOPRECORDING jetzt", "STOPRECORDING"));
        assertTrue(CommandDetector.containsCommand("STOPRECORDING", "STOPRECORDING"));
    }

    @Test
    void istCaseInsensitive() {
        assertTrue(CommandDetector.containsCommand("stoprecording bitte", "STOPRECORDING"));
        assertTrue(CommandDetector.containsCommand("StopRecording", "STOPRECORDING"));
    }

    @Test
    void ignoriertTeilwortTreffer() {
        assertFalse(CommandDetector.containsCommand("NOSTOPRECORDING", "STOPRECORDING"));
        assertFalse(CommandDetector.containsCommand("STOPRECORDINGS", "STOPRECORDING"));
    }

    @Test
    void istSicherGegenRegexInjection() {
        assertFalse(CommandDetector.containsCommand("irgendwas", "ST.P(RECORDING"));
        assertTrue(CommandDetector.containsCommand("bitte ST.P(RECORDING jetzt", "ST.P(RECORDING"));
    }

    @Test
    void findetBefehlMitNichtWortZeichenAmRand() {
        // Vor "!" gibt es keine \b-Wortgrenze - starres \b wuerde nie matchen.
        assertTrue(CommandDetector.containsCommand("!start", "!start"));
        assertTrue(CommandDetector.containsCommand("bitte !start jetzt", "!start"));
        assertTrue(CommandDetector.containsCommand("aufnahme stop!", "stop!"));
        // Wortgrenze am Wort-Ende des Befehls bleibt wirksam:
        assertFalse(CommandDetector.containsCommand("!startrecording", "!start"));
    }

    @Test
    void normalisiertWhitespace() {
        assertTrue(CommandDetector.containsCommand("bitte   STOPRECORDING\n jetzt", "STOPRECORDING"));
    }

    @Test
    void leererInputFindetNichts() {
        assertFalse(CommandDetector.containsCommand("", "STOPRECORDING"));
        assertFalse(CommandDetector.containsCommand(null, "STOPRECORDING"));
        assertFalse(CommandDetector.containsCommand("text", ""));
        assertFalse(CommandDetector.containsCommand("text", null));
    }

    @Test
    void textAfterMarkerLiefertNullWennMarkerFehlt() {
        assertNull(CommandDetector.textAfterMarker("Chat ohne Marker", "RECabc123"));
        assertNull(CommandDetector.textAfterMarker(null, "RECabc123"));
        assertNull(CommandDetector.textAfterMarker("Chat", null));
    }

    @Test
    void textAfterMarkerSchneidetKorrekt() {
        String chat = "Bot: Warnung [RECabc123]\nUser: STOPRECORDING";
        assertEquals("]\nUser: STOPRECORDING", CommandDetector.textAfterMarker(chat, "RECabc123"));
    }

    @Test
    void detectAfterMarkerFindetStopNurNachMarker() {
        String chat = "User: STOPRECORDING (alt)\nBot: Warnung [RECxyz789]\nUser: alles gut";
        CommandDetector.Detection det = CommandDetector.detectAfterMarker(
                chat, "RECxyz789", "STOPRECORDING", "STARTRECORDING");
        assertTrue(det.markerFound());
        assertFalse(det.foundStop());
        assertFalse(det.foundStart());
    }

    @Test
    void detectAfterMarkerFindetStopNachMarker() {
        String chat = "Bot: Warnung [RECxyz789]\nUser: STOPRECORDING bitte";
        CommandDetector.Detection det = CommandDetector.detectAfterMarker(
                chat, "RECxyz789", "STOPRECORDING", "STARTRECORDING");
        assertTrue(det.markerFound());
        assertTrue(det.foundStop());
    }

    @Test
    void detectAfterMarkerOhneMarkerImChat() {
        CommandDetector.Detection det = CommandDetector.detectAfterMarker(
                "User: STOPRECORDING", "RECfehlt", "STOPRECORDING", "STARTRECORDING");
        assertFalse(det.markerFound());
        assertFalse(det.foundStop());
    }

    @Test
    void selbstTriggerSchutz_startBefehlInBotHinweisVorCutoff() {
        // Bot-Hinweis enthaelt STARTRECORDING, danach kommt der Cutoff-Marker:
        // Suche nach dem Cutoff darf den Hinweis nicht mehr finden
        String chat = "Bot: Aufzeichnung verworfen. Mit STARTRECORDING neu starten. [RECcut111]\nUser: ok";
        String after = CommandDetector.textAfterMarker(chat, "RECcut111");
        assertFalse(CommandDetector.containsCommand(after, "STARTRECORDING"));
    }
}
