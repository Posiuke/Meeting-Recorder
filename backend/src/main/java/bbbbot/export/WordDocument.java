package bbbbot.export;

import java.nio.charset.StandardCharsets;

/**
 * Verpackt ein HTML-Fragment in eine Datei, die Word (und LibreOffice) direkt
 * oeffnen: HTML mit {@code application/msword} und der Endung {@code .doc}.
 *
 * <p>Warum kein echtes DOCX: Das braeuchte eine Bibliothek (POI &amp; Co.), die in
 * den abgeschotteten Zielnetzen erst durch den internen Repository-Manager
 * muesste. Dieser Weg kostet keine einzige Abhaengigkeit, erhaelt Ueberschriften,
 * Listen und Tabellen als echte Word-Elemente und laesst sich in Word ohne
 * Umwege als DOCX oder PDF weiterspeichern.
 *
 * <p>Der Word-spezifische Kopf ({@code o:} / {@code w:}-Namensraeume,
 * {@code ProgId}) sorgt dafuer, dass Word die Datei als eigenes Dokument
 * behandelt statt als "aus dem Web importiert".
 */
public final class WordDocument {

    private WordDocument() {}

    /** Dateiendung und Inhaltstyp gehoeren zusammen - beides hier, damit es nicht auseinanderlaeuft. */
    public static final String EXTENSION = "doc";
    public static final String CONTENT_TYPE = "application/msword; charset=utf-8";

    private static final String STYLES = """
            body { font-family: Calibri, 'Segoe UI', Arial, sans-serif; font-size: 11pt; color: #1f2933; }
            h1 { font-size: 20pt; margin: 0 0 4pt 0; }
            h2 { font-size: 15pt; margin: 16pt 0 4pt 0; }
            h3 { font-size: 12.5pt; margin: 12pt 0 4pt 0; }
            h4, h5, h6 { font-size: 11pt; margin: 10pt 0 4pt 0; }
            p { margin: 0 0 8pt 0; }
            ul, ol { margin: 0 0 8pt 24pt; padding: 0; }
            li { margin: 0 0 3pt 0; }
            table { border-collapse: collapse; margin: 0 0 10pt 0; }
            th, td { border: 1px solid #b9c2cc; padding: 4pt 6pt; vertical-align: top; }
            th { background: #eef2f6; text-align: left; }
            pre { background: #f4f6f9; border: 1px solid #dde3ea; padding: 6pt; white-space: pre-wrap; }
            code { font-family: Consolas, 'Courier New', monospace; font-size: 10pt; }
            blockquote { margin: 0 0 8pt 16pt; padding-left: 8pt; border-left: 3px solid #b9c2cc; color: #4a5561; }
            .meta { color: #67717f; font-size: 9.5pt; margin: 0 0 14pt 0; }
            .time { color: #67717f; font-family: Consolas, 'Courier New', monospace; font-size: 9.5pt; }
            .speaker { font-weight: bold; }
            """;

    /**
     * @param title    Titel des Dokuments (Kopfzeile und Dateieigenschaften).
     *                 Bringt der Inhalt bereits eine eigene Hauptueberschrift mit
     *                 - Zusammenfassungen beginnen meist mit einer -, wird sie
     *                 nicht ein zweites Mal davorgesetzt.
     * @param subtitle Zeile unter dem Titel, z.B. Datum und Fassung (darf null sein)
     * @param bodyHtml fertiges HTML-Fragment fuer den Inhalt
     */
    public static byte[] render(String title, String subtitle, String bodyHtml) {
        boolean bodyHasHeading = bodyHtml != null && bodyHtml.stripLeading().startsWith("<h1>");
        String head = """
                <html xmlns:o="urn:schemas-microsoft-com:office:office" \
                xmlns:w="urn:schemas-microsoft-com:office:word" \
                xmlns="http://www.w3.org/TR/REC-html40">
                <head>
                <meta charset="utf-8"/>
                <meta name="ProgId" content="Word.Document"/>
                <meta name="Generator" content="Meeting Recorder"/>
                <title>%s</title>
                <style>%s</style>
                </head>
                <body>
                """.formatted(MarkdownHtml.escape(title), STYLES);

        String meta = subtitle == null || subtitle.isBlank()
                ? "" : "<p class=\"meta\">" + MarkdownHtml.escape(subtitle) + "</p>\n";
        String body = bodyHtml == null ? "" : bodyHtml;

        if (bodyHasHeading) {
            // Eigene Hauptueberschrift: Die Datumszeile gehoert darunter, nicht davor.
            int end = body.indexOf("</h1>");
            body = end < 0 ? meta + body
                    : body.substring(0, end + 5) + "\n" + meta + body.substring(end + 5).stripLeading();
            return (head + body + "\n</body>\n</html>\n").getBytes(StandardCharsets.UTF_8);
        }
        String heading = "<h1>" + MarkdownHtml.escape(title) + "</h1>\n";
        return (head + heading + meta + body + "\n</body>\n</html>\n").getBytes(StandardCharsets.UTF_8);
    }
}
