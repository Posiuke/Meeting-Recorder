package bbbbot.processing;

import java.time.LocalTime;

/**
 * Zeitfenster-Logik fuer die ressourcenschonende Verarbeitung:
 * STT und LLM-Auswertung laufen nur im Admin-definierten Fenster
 * (z.B. 20:00-06:00, darf ueber Mitternacht gehen). Jobs mit
 * immediate=true ("Sofort auswerten") ignorieren das Fenster.
 */
public final class ProcessingWindow {

    private ProcessingWindow() {}

    public static boolean isWithinWindow(LocalTime now, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return true; // identische Zeiten = rund um die Uhr
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // Fenster ueber Mitternacht (z.B. 20:00-06:00)
        return !now.isBefore(start) || now.isBefore(end);
    }
}
