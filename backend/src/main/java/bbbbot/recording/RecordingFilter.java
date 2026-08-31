package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingTag;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;

/**
 * Geprueftes Filterbild der Aufnahmenliste - was der Nutzer in der Filterleiste
 * eingestellt hat, in einer Form, die {@link RecordingSearch} direkt in eine
 * Abfrage uebersetzen kann.
 *
 * <p>Die Normalisierung passiert genau hier und nicht im Controller: Der
 * Suchbegriff wird kleingeschrieben und gekuerzt, das Schlagwort auf seinen
 * Vergleichsschluessel gebracht, Zeitraum und Quelle geprueft. {@code null}
 * heisst durchgaengig "dieser Filter ist nicht gesetzt".
 *
 * <p>Bewusst ohne Spring-Bezug: Ungueltige Eingaben werfen
 * {@link IllegalArgumentException}, die Uebersetzung in einen HTTP-Status ist
 * Sache des Controllers.
 */
public record RecordingFilter(
        /** Kleingeschriebener, gekuerzter Suchbegriff; null = kein Textfilter. */
        String text,
        /** Vergleichsschluessel des Schlagworts; null = kein Schlagwortfilter. */
        String tagKey,
        /** Zusaetzlich in Transkript und Zusammenfassung suchen. */
        boolean includeContent,
        /** Aufnahmen ab diesem Zeitpunkt (einschliesslich); null = ohne Untergrenze. */
        Instant from,
        /** Aufnahmen vor diesem Zeitpunkt (ausschliesslich); null = ohne Obergrenze. */
        Instant to,
        /** Nur diese Quelle (Bot, Upload, Bildschirm); null = alle. */
        Recording.Source source,
        /** Eigene, geteilte oder alle Aufnahmen. */
        OwnerScope ownerScope,
        /** Genau dieser Besitzer; null = egal. */
        UUID ownerId) {

    /** Wessen Aufnahmen die Liste zeigt. */
    public enum OwnerScope {
        /** Alles, was der Nutzer sehen darf. */
        ALL,
        /** Nur die eigenen. */
        MINE,
        /** Nur die, die andere mit ihm geteilt haben. */
        SHARED
    }

    /** Kein Filter gesetzt - die ganze Liste. */
    public static final RecordingFilter NONE =
            new RecordingFilter(null, null, false, null, null, null, OwnerScope.ALL, null);

    /**
     * Baut das Filterbild aus den Rohwerten der Anfrage.
     *
     * @param q       Suchbegriff fuer Titel, Meeting-URL und Schlagworte
     * @param tag     genau dieses Schlagwort
     * @param content zusaetzlich Transkript und Zusammenfassung durchsuchen
     * @param from    Untergrenze: ISO-Zeitpunkt oder Datum (JJJJ-MM-TT)
     * @param to      Obergrenze: ISO-Zeitpunkt oder Datum - ein Datum meint den
     *                ganzen Tag, die Grenze rueckt dann auf den Folgetag
     * @param source  BOT, UPLOAD oder CAPTURE
     * @param owner   {@code mine}, {@code shared} oder die Kennung eines Besitzers
     * @throws IllegalArgumentException bei einer Angabe, die sich nicht deuten laesst
     */
    public static RecordingFilter of(String q, String tag, boolean content,
                                     String from, String to, String source, String owner) {
        Instant fromInstant = parseBound(from, false, "from");
        Instant toInstant = parseBound(to, true, "to");
        if (fromInstant != null && toInstant != null && !toInstant.isAfter(fromInstant)) {
            throw new IllegalArgumentException("Der Zeitraum endet vor seinem Beginn");
        }
        OwnerScope scope = OwnerScope.ALL;
        UUID ownerId = null;
        String ownerRaw = blankToNull(owner);
        if (ownerRaw != null) {
            switch (ownerRaw.toLowerCase(Locale.ROOT)) {
                case "mine" -> scope = OwnerScope.MINE;
                case "shared" -> scope = OwnerScope.SHARED;
                case "all" -> scope = OwnerScope.ALL;
                default -> {
                    // Ein bestimmter Besitzer ist immer eine Fremdfreigabe-Ansicht;
                    // die eigene Kennung darf trotzdem stehen und meint dann "meine".
                    ownerId = parseUuid(ownerRaw);
                }
            }
        }
        return new RecordingFilter(normalizeText(q), normalizeTag(tag), content,
                fromInstant, toInstant, parseSource(source), scope, ownerId);
    }

    /** Kleingeschrieben und auf {@link RecordingSearch#MAX_QUERY_LENGTH} gekuerzt. */
    private static String normalizeText(String raw) {
        String text = raw == null ? "" : raw.strip().toLowerCase(Locale.GERMAN);
        if (text.isEmpty()) return null;
        return text.length() > RecordingSearch.MAX_QUERY_LENGTH
                ? text.substring(0, RecordingSearch.MAX_QUERY_LENGTH)
                : text;
    }

    private static String normalizeTag(String raw) {
        String key = RecordingTag.normalizeKey(raw);
        return key.isEmpty() ? null : key;
    }

    private static Recording.Source parseSource(String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        try {
            return Recording.Source.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannte Quelle: " + raw
                    + " (erwartet BOT, UPLOAD oder CAPTURE)");
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannter Besitzerfilter: " + raw
                    + " (erwartet mine, shared oder eine Nutzerkennung)");
        }
    }

    /**
     * Zeitgrenze aus ISO-Zeitpunkt ({@code 2026-08-31T08:00:00Z}) oder Datum
     * ({@code 2026-08-31}). Ein Datum wird in der Zeitzone dieses Servers
     * ausgelegt - dieselbe, in der die Oberflaeche die Zeiten anzeigt.
     *
     * @param endOfDay bei einem Datum die Grenze auf den Folgetag setzen, damit
     *                 der angegebene Tag vollstaendig im Zeitraum liegt
     */
    private static Instant parseBound(String raw, boolean endOfDay, String field) {
        String value = blankToNull(raw);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // kein Zeitpunkt - dann als reines Datum versuchen
        }
        try {
            LocalDate date = LocalDate.parse(value);
            LocalDate boundary = endOfDay ? date.plusDays(1) : date;
            return boundary.atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Ungueltige Zeitangabe fuer " + field + ": " + raw
                    + " (erwartet JJJJ-MM-TT oder einen ISO-Zeitpunkt)");
        }
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
