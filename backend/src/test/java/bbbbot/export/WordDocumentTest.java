package bbbbot.export;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WordDocumentTest {

    @Test
    void setztTitelUndUntertitelDavor() {
        String html = render("Wochenbesprechung", "2026-07-21", "<p>Inhalt</p>");

        assertThat(html).contains("<title>Wochenbesprechung</title>");
        assertThat(html).contains("<h1>Wochenbesprechung</h1>");
        assertThat(html).contains("<p class=\"meta\">2026-07-21</p>");
        assertThat(html).contains("Word.Document");
    }

    @Test
    void eigeneHauptueberschriftBleibtEinmalig() {
        // Zusammenfassungen beginnen fast immer mit "# Titel" - dann darf der
        // Dokumenttitel nicht ein zweites Mal davorstehen.
        String html = render("Wochenbesprechung", "2026-07-21",
                MarkdownHtml.toHtml("# Wochenbesprechung\n\nInhalt"));

        assertThat(countOccurrences(html, "<h1>Wochenbesprechung</h1>")).isEqualTo(1);
        // Die Datumszeile steht dann unter der Ueberschrift des Inhalts
        assertThat(html).contains("<h1>Wochenbesprechung</h1>\n<p class=\"meta\">2026-07-21</p>");
    }

    @Test
    void maskiertTitelMitSonderzeichen() {
        String html = render("Angebot <Nord> & Süd", null, "<p>Inhalt</p>");

        assertThat(html).contains("<h1>Angebot &lt;Nord&gt; &amp; Süd</h1>");
        assertThat(html).doesNotContain("<p class=\"meta\">");
    }

    private static String render(String title, String subtitle, String body) {
        return new String(WordDocument.render(title, subtitle, body), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String haystack, String needle) {
        int found = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            found++;
            from += needle.length();
        }
        return found;
    }
}
