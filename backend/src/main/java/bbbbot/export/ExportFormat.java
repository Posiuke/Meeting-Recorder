package bbbbot.export;

import java.util.Locale;

/**
 * Ausgabeformat der Download-Endpunkte fuer Transkript und Zusammenfassung.
 * Markdown ist die Rohfassung (so liegt der Text auch auf der Platte), Word die
 * Fassung fuers Haus - Protokolle wandern in der Praxis nach Word, Confluence
 * oder Nextcloud, und Markdown ist dort eine Huerde.
 */
public enum ExportFormat {

    MARKDOWN("md", "text/markdown; charset=utf-8"),
    WORD(WordDocument.EXTENSION, WordDocument.CONTENT_TYPE);

    private final String extension;
    private final String contentType;

    ExportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() { return extension; }

    public String contentType() { return contentType; }

    /**
     * @param raw {@code md}/{@code markdown} oder {@code doc}/{@code word};
     *            leer = Markdown (unveraendertes Verhalten der alten Endpunkte)
     * @throws IllegalArgumentException bei allem anderen
     */
    public static ExportFormat parse(String raw) {
        if (raw == null || raw.isBlank()) return MARKDOWN;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "md", "markdown" -> MARKDOWN;
            case "doc", "word" -> WORD;
            default -> throw new IllegalArgumentException(
                    "Unbekanntes Format: " + raw + " (erlaubt: md, doc)");
        };
    }
}
