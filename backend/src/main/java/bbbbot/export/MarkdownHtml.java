package bbbbot.export;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wandelt das Markdown der Zusammenfassung in HTML um - fuer den Word-Export
 * ({@link WordDocument}).
 *
 * <p>Bewusst selbst geschrieben statt per Bibliothek: Die Anwendung laeuft in
 * abgeschotteten Netzen, in denen jede neue Abhaengigkeit erst durch den
 * internen Repository-Manager muss. Abgedeckt ist genau der Satz, den die
 * Weboberflaeche anzeigt (Markdown-Kern + GFM-Tabellen, -Aufgabenlisten und
 * -Durchstreichungen) - mehr liefern die Modelle nicht.
 *
 * <p>Rohes HTML im Markdown wird NICHT durchgereicht, sondern maskiert: Der Text
 * stammt aus einem Sprachmodell und ist vom Besitzer frei bearbeitbar. Damit
 * kann aus einer Zusammenfassung keine Datei mit fremdem Markup entstehen -
 * dieselbe Entscheidung wie in der Anzeige (dort kein rehype-raw).
 */
public final class MarkdownHtml {

    private MarkdownHtml() {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)\\s*(\\S*).*$");
    private static final Pattern UNORDERED = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)\\d+[.)]\\s+(.*)$");
    private static final Pattern TASK = Pattern.compile("^\\[( |x|X)]\\s+(.*)$");
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    /** Inline-Auszeichnungen; Codespans werden vorher herausgenommen (siehe {@link #inline}). */
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(\\S(?:.*?\\S)?)\\*\\*", Pattern.DOTALL);
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*(\\S(?:.*?\\S)?)\\*(?![\\w*])", Pattern.DOTALL);
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("(?<![\\w_])_(\\S(?:.*?\\S)?)_(?![\\w_])", Pattern.DOTALL);
    private static final Pattern STRIKE = Pattern.compile("~~(\\S(?:.*?\\S)?)~~", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    /** Platzhalter fuer bereits fertige Codespans (Zeichen, das in Text nicht vorkommt). */
    private static final char PLACEHOLDER = '\0';

    /**
     * Uebersetzt Markdown in ein HTML-Fragment (ohne html/body-Rahmen).
     */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        List<String> lines = List.of(markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                flushParagraph(paragraph, out);
                i = appendCodeBlock(lines, i, fence.group(1), out);
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(paragraph, out);
                continue;
            }
            if (RULE.matcher(line).matches()) {
                flushParagraph(paragraph, out);
                out.append("<hr/>\n");
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(paragraph, out);
                int level = heading.group(1).length();
                out.append("<h").append(level).append('>').append(inline(heading.group(2)))
                        .append("</h").append(level).append(">\n");
                continue;
            }
            if (line.startsWith(">")) {
                flushParagraph(paragraph, out);
                i = appendQuote(lines, i, out);
                continue;
            }
            if (isTableStart(lines, i)) {
                flushParagraph(paragraph, out);
                i = appendTable(lines, i, out);
                continue;
            }
            if (UNORDERED.matcher(line).matches() || ORDERED.matcher(line).matches()) {
                flushParagraph(paragraph, out);
                i = appendList(lines, i, out);
                continue;
            }
            paragraph.add(line);
        }
        flushParagraph(paragraph, out);
        return out.toString();
    }

    // ------------------------------------------------------------- Bloecke

    /** @return Index der schliessenden Zaunzeile (oder der letzten Zeile) */
    private static int appendCodeBlock(List<String> lines, int start, String fence, StringBuilder out) {
        StringBuilder code = new StringBuilder();
        int i = start + 1;
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().startsWith(fence)) break;
            code.append(line).append('\n');
        }
        out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>\n");
        return Math.min(i, lines.size() - 1);
    }

    private static int appendQuote(List<String> lines, int start, StringBuilder out) {
        List<String> content = new ArrayList<>();
        int i = start;
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith(">")) break;
            content.add(line.substring(1).stripLeading());
        }
        out.append("<blockquote><p>").append(inline(String.join(" ", content))).append("</p></blockquote>\n");
        return i - 1;
    }

    /**
     * Liste mit Verschachtelung ueber die Einrueckung. Aufgabenlisten
     * ({@code - [ ]}) werden zu Kaestchen-Zeichen - Word kennt keine
     * Markdown-Checkboxen, ein Zeichen versteht dagegen jedes Programm.
     *
     * <p>Eine Unterliste gehoert INNERHALB des uebergeordneten {@code <li>}; der
     * Eintrag bleibt deshalb offen, bis feststeht, ob eine tiefere Ebene folgt.
     * Word ist bei falsch verschachtelten Listen nachsichtig, ruecken tut es sie
     * dann aber nicht.
     */
    private static int appendList(List<String> lines, int start, StringBuilder out) {
        record Level(int indent, String tag) {}
        Deque<Level> open = new ArrayDeque<>();
        // Ist auf der aktuellen Ebene ein <li> noch offen?
        boolean itemOpen = false;
        int i = start;

        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher unordered = UNORDERED.matcher(line);
            Matcher ordered = ORDERED.matcher(line);
            boolean isUnordered = unordered.matches();
            if (!isUnordered && !ordered.matches()) {
                if (line.isBlank() && i + 1 < lines.size()
                        && (UNORDERED.matcher(lines.get(i + 1)).matches()
                            || ORDERED.matcher(lines.get(i + 1)).matches())) {
                    // Leerzeile innerhalb der Liste (lockere Liste) - weiterlesen
                    continue;
                }
                break;
            }
            Matcher item = isUnordered ? unordered : ordered;
            int indent = item.group(1).length();
            String tag = isUnordered ? "ul" : "ol";

            if (open.isEmpty() || open.peek().indent() < indent) {
                // Tiefere Ebene: Sie wird in den offenen Eintrag hineingeschrieben.
                if (itemOpen) out.append('\n');
                open.push(new Level(indent, tag));
                out.append('<').append(tag).append(">\n");
                itemOpen = false;
            } else {
                while (open.size() > 1 && open.peek().indent() > indent) {
                    if (itemOpen) out.append("</li>\n");
                    out.append("</").append(open.pop().tag()).append(">\n");
                    // Zurueck auf der Elternebene, deren Eintrag noch offen ist
                    itemOpen = true;
                }
                if (itemOpen) {
                    out.append("</li>\n");
                    itemOpen = false;
                }
                if (!open.peek().tag().equals(tag)) {
                    // Wechsel der Listenart auf gleicher Ebene
                    out.append("</").append(open.pop().tag()).append(">\n");
                    open.push(new Level(indent, tag));
                    out.append('<').append(tag).append(">\n");
                }
            }
            out.append("<li>").append(inline(taskMarker(item.group(2))));
            itemOpen = true;
        }
        while (!open.isEmpty()) {
            if (itemOpen) out.append("</li>\n");
            out.append("</").append(open.pop().tag()).append(">\n");
            itemOpen = !open.isEmpty();
        }
        return i - 1;
    }

    /** "[x] Text" -> "☑ Text" (bzw. ☐); alles andere bleibt unveraendert. */
    private static String taskMarker(String text) {
        Matcher task = TASK.matcher(text);
        if (!task.matches()) return text;
        return ("x".equalsIgnoreCase(task.group(1)) ? "☑ " : "☐ ") + task.group(2);
    }

    private static boolean isTableStart(List<String> lines, int index) {
        return lines.get(index).contains("|")
                && index + 1 < lines.size()
                && lines.get(index + 1).contains("-")
                && TABLE_DIVIDER.matcher(lines.get(index + 1)).matches();
    }

    private static int appendTable(List<String> lines, int start, StringBuilder out) {
        List<String> alignments = alignments(lines.get(start + 1));
        out.append("<table><thead><tr>");
        appendCells(splitRow(lines.get(start)), alignments, "th", out);
        out.append("</tr></thead><tbody>\n");

        int i = start + 2;
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || !line.contains("|")) break;
            out.append("<tr>");
            appendCells(splitRow(line), alignments, "td", out);
            out.append("</tr>\n");
        }
        out.append("</tbody></table>\n");
        return i - 1;
    }

    private static void appendCells(List<String> cells, List<String> alignments, String tag, StringBuilder out) {
        for (int c = 0; c < cells.size(); c++) {
            String align = c < alignments.size() ? alignments.get(c) : null;
            out.append('<').append(tag);
            if (align != null) out.append(" style=\"text-align:").append(align).append('"');
            out.append('>').append(inline(cells.get(c))).append("</").append(tag).append('>');
        }
    }

    /** Ausrichtung je Spalte aus der Trennzeile (:---, :---:, ---:). */
    private static List<String> alignments(String divider) {
        List<String> result = new ArrayList<>();
        for (String cell : splitRow(divider)) {
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            result.add(left && right ? "center" : right ? "right" : left ? "left" : null);
        }
        return result;
    }

    private static List<String> splitRow(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static void flushParagraph(List<String> paragraph, StringBuilder out) {
        if (paragraph.isEmpty()) return;
        // Weiche Zeilenumbrueche werden - wie in der Anzeige - zu Leerzeichen.
        out.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>\n");
        paragraph.clear();
    }

    // -------------------------------------------------------------- Inline

    /**
     * Inline-Auszeichnungen eines Absatzes. Codespans werden zuerst
     * herausgenommen und erst am Ende wieder eingesetzt - sonst wuerde
     * {@code `a * b`} als Kursivschrift enden.
     */
    static String inline(String text) {
        List<String> codes = new ArrayList<>();
        Matcher code = CODE_SPAN.matcher(text);
        StringBuilder withPlaceholders = new StringBuilder();
        while (code.find()) {
            code.appendReplacement(withPlaceholders,
                    Matcher.quoteReplacement(PLACEHOLDER + String.valueOf(codes.size()) + PLACEHOLDER));
            codes.add(code.group(1));
        }
        code.appendTail(withPlaceholders);

        String html = escape(withPlaceholders.toString());
        html = BOLD.matcher(html).replaceAll("<strong>$1</strong>");
        html = STRIKE.matcher(html).replaceAll("<s>$1</s>");
        html = ITALIC.matcher(html).replaceAll("<em>$1</em>");
        html = ITALIC_UNDERSCORE.matcher(html).replaceAll("<em>$1</em>");
        html = LINK.matcher(html).replaceAll(match ->
                "<a href=\"" + escapeAttribute(match.group(2)) + "\">"
                        + (match.group(1).isBlank() ? escape(match.group(2)) : match.group(1)) + "</a>");

        for (int i = 0; i < codes.size(); i++) {
            html = html.replace(PLACEHOLDER + String.valueOf(i) + PLACEHOLDER,
                    "<code>" + escape(codes.get(i)) + "</code>");
        }
        return html;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** Nur fuer Attributwerte: zusaetzlich Anfuehrungszeichen maskieren. */
    private static String escapeAttribute(String value) {
        return escape(value).replace("\"", "&quot;");
    }
}
