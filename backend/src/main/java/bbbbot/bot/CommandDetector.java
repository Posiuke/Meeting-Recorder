package bbbbot.bot;

import java.util.regex.Pattern;

/**
 * Reine Textlogik der Chat-Befehls-Erkennung (Portierung von
 * src/chat/commandDetection.ts): Wortgrenzen-Matching, case-insensitive,
 * Regex-Injection-sicher, Suche nur im Text NACH einem Marker.
 */
public final class CommandDetector {

    private CommandDetector() {}

    /** Whitespace normalisieren, Wortgrenzen erhalten. */
    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    // Bot-eigene Hinweismeldungen tragen immer einen Session-Marker "[RECxxxxxxxxxxxx]"
    // (siehe SessionMarkers). Nutzer tippen den Befehl OHNE Marker. Zeilen mit
    // Marker sind also Bot-Nachrichten und werden bei der Befehlserkennung ignoriert.
    private static final Pattern BOT_MARKER_LINE = Pattern.compile("\\[REC[0-9A-Za-z]{12}\\]");

    /**
     * Entfernt alle Chat-Zeilen, die einen Bot-Marker enthalten (= Bot-eigene
     * Hinweismeldungen), damit der Bot nicht auf seine eigenen STOP/START-Hinweise
     * reagiert. Erwartet Text mit einer Nachricht pro Zeile.
     */
    public static String stripBotMarkerLines(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (BOT_MARKER_LINE.matcher(line).find()) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Befehl mit Wortgrenzen suchen (verhindert Treffer in "NOSTOPRECORDING").
     * \b gilt nur zwischen Wort- und Nicht-Wort-Zeichen: Beginnt/endet der
     * Befehl mit einem Nicht-Wort-Zeichen (z.B. "!start"), darf dort KEIN \b
     * stehen, sonst matcht der Befehl nie.
     */
    public static boolean containsCommand(String text, String command) {
        if (text == null || text.isEmpty() || command == null || command.isEmpty()) return false;
        String prefix = isWordChar(command.charAt(0)) ? "\\b" : "";
        String suffix = isWordChar(command.charAt(command.length() - 1)) ? "\\b" : "";
        Pattern p = Pattern.compile(prefix + Pattern.quote(command) + suffix, Pattern.CASE_INSENSITIVE);
        return p.matcher(normalize(text)).find();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Liefert den Text nach dem Marker oder null, wenn der Marker (noch) nicht
     * im Chat sichtbar ist. Null bedeutet: keine Aussage moeglich, Befehle
     * duerfen NICHT ausgewertet werden (Datenschutz/Selbst-Trigger-Schutz).
     */
    public static String textAfterMarker(String chatText, String marker) {
        if (chatText == null || marker == null || marker.isEmpty()) return null;
        int pos = chatText.indexOf(marker);
        if (pos < 0) return null;
        return chatText.substring(pos + marker.length());
    }

    public record Detection(boolean markerFound, boolean foundStop, boolean foundStart) {}

    public static Detection detectAfterMarker(String chatText, String marker, String stopCommand, String startCommand) {
        String after = textAfterMarker(chatText, marker);
        if (after == null) return new Detection(false, false, false);
        // Bot-eigene Hinweismeldungen (mit Marker) ausblenden -> kein Selbst-Trigger.
        String userText = stripBotMarkerLines(after);
        return new Detection(true, containsCommand(userText, stopCommand), containsCommand(userText, startCommand));
    }
}
