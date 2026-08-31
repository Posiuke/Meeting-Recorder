package bbbbot.recording;

import java.util.Locale;

/**
 * Sortierung der Aufnahmenliste: die Oberflaeche schickt einen kurzen
 * Schluessel ({@code date}, {@code title}) und eine Richtung.
 *
 * <p>Bewusst kein Spring-{@code Sort}: Nach Titel wird ueber einen Ausdruck
 * sortiert ({@code coalesce(title, meetingUrl)}) und nicht ueber die Spalte
 * allein. Zwei Gruende - eine Bot-Aufnahme ohne erkannten Raumnamen hat keinen
 * Titel und wuerde sonst je nach Richtung an den Anfang rutschen, und die Liste
 * zeigt in dieser Spalte ohnehin die Meeting-URL an, wenn der Titel fehlt.
 * Sortiert wird damit nach dem, was dort steht. Ein {@code Sort} kann nur
 * Spalten benennen, keine Ausdruecke - und Nullwert-Vorrang
 * ({@code nulls last}) unterstuetzt Spring Data bei Criteria-Abfragen nicht.
 *
 * <p>Die Umsetzung in eine Abfrage macht {@link RecordingSearch}.
 */
public record RecordingSort(String key, boolean ascending) {

    /** Zeitpunkt der Aufnahme - die Vorgabe der Liste. */
    public static final String BY_DATE = "date";
    /** Titel bzw. Raumname. */
    public static final String BY_TITLE = "title";

    /** Neueste zuerst. */
    public static final RecordingSort DEFAULT = new RecordingSort(BY_DATE, false);

    /**
     * @param key Sortierschluessel ({@code date} oder {@code title}); leer = Datum
     * @param dir {@code asc} oder {@code desc}; leer = {@code desc} beim Datum,
     *            {@code asc} beim Titel (jeweils die erwartete Leserichtung)
     * @throws IllegalArgumentException bei unbekanntem Schluessel oder Richtung
     */
    public static RecordingSort of(String key, String dir) {
        String sortKey = key == null || key.isBlank() ? BY_DATE : key.trim().toLowerCase(Locale.ROOT);
        boolean ascendingByDefault = switch (sortKey) {
            case BY_DATE -> false;
            case BY_TITLE -> true;
            default -> throw new IllegalArgumentException("Unbekannte Sortierung: " + key
                    + " (erwartet " + BY_DATE + " oder " + BY_TITLE + ")");
        };
        return new RecordingSort(sortKey, ascending(dir, ascendingByDefault));
    }

    private static boolean ascending(String dir, boolean fallback) {
        if (dir == null || dir.isBlank()) return fallback;
        return switch (dir.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> true;
            case "desc" -> false;
            default -> throw new IllegalArgumentException("Unbekannte Sortierrichtung: " + dir
                    + " (erwartet asc oder desc)");
        };
    }

    public boolean byTitle() {
        return BY_TITLE.equals(key);
    }
}
