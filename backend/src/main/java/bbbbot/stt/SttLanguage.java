package bbbbot.stt;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sprachangabe fuer die Spracherkennung (Whisper). Drei Zustaende:
 *
 * <ul>
 *   <li>{@code null} - keine Wahl getroffen, es gilt der Admin-Standard
 *       (Einstellung {@code whisper.language}),</li>
 *   <li>{@link #AUTO} - Whisper soll die Sprache selbst erkennen,</li>
 *   <li>ein Sprachcode wie {@code de}, {@code en}, {@code pt-br}.</li>
 * </ul>
 *
 * <p>Bewusst keine feste Liste erlaubter Codes: Welche Sprachen das Modell
 * kennt, entscheidet der Whisper-Server, nicht diese Anwendung. Geprueft wird
 * nur die Form, damit nichts Beliebiges in die Anfrage-URL wandert.
 */
public final class SttLanguage {

    private SttLanguage() {}

    /** Sprache automatisch erkennen lassen - Whisper bekommt dann keine Vorgabe. */
    public static final String AUTO = "auto";

    /** ISO-639-Code, optional mit Regionszusatz ("de", "pt-br", "zh-hans"). */
    private static final Pattern CODE = Pattern.compile("[a-z]{2,3}(-[a-z0-9]{2,8})?");

    /**
     * Normalisiert eine Sprachangabe fuer die Ablage an Aufnahme oder Bot-Session.
     *
     * @return kleingeschriebener Code, {@link #AUTO} oder {@code null} fuer
     *         "Admin-Standard verwenden"
     * @throws IllegalArgumentException wenn die Angabe keine Sprachangabe sein kann
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (AUTO.equals(value)) return AUTO;
        if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Ungueltige Sprachangabe fuer die Spracherkennung: " + raw
                            + " (erwartet z.B. de, en oder " + AUTO + ")");
        }
        return value;
    }
}
