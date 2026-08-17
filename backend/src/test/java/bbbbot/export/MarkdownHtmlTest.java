package bbbbot.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownHtmlTest {

    @Test
    void ueberschriftenUndAbsaetze() {
        String html = MarkdownHtml.toHtml("""
                # Protokoll
                ## Beschluesse

                Erste Zeile
                zweite Zeile.

                Neuer Absatz.""");

        assertThat(html).contains("<h1>Protokoll</h1>");
        assertThat(html).contains("<h2>Beschluesse</h2>");
        // Weicher Zeilenumbruch wird zum Leerzeichen - wie in der Anzeige
        assertThat(html).contains("<p>Erste Zeile zweite Zeile.</p>");
        assertThat(html).contains("<p>Neuer Absatz.</p>");
    }

    @Test
    void inlineAuszeichnungen() {
        String html = MarkdownHtml.toHtml("**fett**, *kursiv*, _auch kursiv_, ~~weg~~ und `code`.");

        assertThat(html).contains("<strong>fett</strong>");
        assertThat(html).contains("<em>kursiv</em>");
        assertThat(html).contains("<em>auch kursiv</em>");
        assertThat(html).contains("<s>weg</s>");
        assertThat(html).contains("<code>code</code>");
    }

    @Test
    void codespanBleibtUnangetastet() {
        String html = MarkdownHtml.toHtml("Der Ausdruck `a * b * c` ist keine Kursivschrift.");

        assertThat(html).contains("<code>a * b * c</code>");
        assertThat(html).doesNotContain("<em>");
    }

    @Test
    void listenMitVerschachtelungUndAufgaben() {
        String html = MarkdownHtml.toHtml("""
                - Punkt eins
                  - Unterpunkt
                - [x] erledigt
                - [ ] offen

                1. erstens
                2. zweitens""");

        // Die Unterliste steht INNERHALB des uebergeordneten Eintrags
        assertThat(html).contains("<li>Punkt eins\n<ul>\n<li>Unterpunkt</li>\n</ul>\n</li>");
        assertThat(html).contains("<li>☑ erledigt</li>").contains("<li>☐ offen</li>");
        assertThat(html).contains("<ol>").contains("<li>erstens</li>").contains("<li>zweitens</li>");
        // Jede geoeffnete Liste wird wieder geschlossen
        assertThat(count(html, "<ul>")).isEqualTo(count(html, "</ul>"));
        assertThat(count(html, "<ol>")).isEqualTo(count(html, "</ol>"));
    }

    @Test
    void tabelleMitAusrichtung() {
        String html = MarkdownHtml.toHtml("""
                | Aufgabe | Wer | Frist |
                |---|:---:|---:|
                | Angebot pruefen | Meier | KW 34 |""");

        assertThat(html).contains("<th>Aufgabe</th>");
        assertThat(html).contains("<th style=\"text-align:center\">Wer</th>");
        assertThat(html).contains("<th style=\"text-align:right\">Frist</th>");
        assertThat(html).contains("<td>Angebot pruefen</td>");
        assertThat(html).contains("</tbody></table>");
    }

    @Test
    void codeblockUndZitatUndTrennlinie() {
        String html = MarkdownHtml.toHtml("""
                ```mermaid
                graph TD; A-->B;
                ```

                > Zitat aus dem Meeting

                ---""");

        assertThat(html).contains("<pre><code>graph TD; A--&gt;B;\n</code></pre>");
        assertThat(html).contains("<blockquote><p>Zitat aus dem Meeting</p></blockquote>");
        assertThat(html).contains("<hr/>");
    }

    @Test
    void linkWirdUebernommen() {
        String html = MarkdownHtml.toHtml("Siehe [Wiki](https://intranet.example/seite).");

        assertThat(html).contains("<a href=\"https://intranet.example/seite\">Wiki</a>");
    }

    @Test
    void rohesHtmlWirdMaskiert() {
        String html = MarkdownHtml.toHtml("Ein <script>alert(1)</script> im Text & ein Zeichen.");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;").contains("&amp;");
    }

    @Test
    void leereEingabeBleibtLeer() {
        assertThat(MarkdownHtml.toHtml(null)).isEmpty();
        assertThat(MarkdownHtml.toHtml("   ")).isEmpty();
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            found++;
            from += needle.length();
        }
        return found;
    }
}
