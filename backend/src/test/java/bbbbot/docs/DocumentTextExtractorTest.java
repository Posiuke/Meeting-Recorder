package bbbbot.docs;

import bbbbot.domain.RecordingDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Textextraktion aus beigefuegten Unterlagen: Text und Markdown liest der Server
 * selbst, alles andere geht an Tika. Kein Text ist ein Fehler - eine Unterlage,
 * von der nichts in der Auswertung ankommt, darf nicht wie ein Erfolg aussehen.
 */
class DocumentTextExtractorTest {

    @TempDir
    Path dir;

    private TikaClient tika;
    private DocumentTextExtractor extractor;

    @BeforeEach
    void setUp() {
        tika = mock(TikaClient.class);
        extractor = new DocumentTextExtractor(tika);
    }

    /** Unterlage mit Datei auf der Platte. */
    private RecordingDocument document(String filename, byte[] content) throws IOException {
        Path file = dir.resolve(filename);
        Files.write(file, content);
        RecordingDocument doc = RecordingDocument.create(UUID.randomUUID(), filename, null, null);
        doc.setStoredPath(file.toString());
        doc.setSizeBytes(content.length);
        return doc;
    }

    @Test
    void liestMarkdownOhneExternenDienst() throws IOException {
        RecordingDocument doc = document("tagesordnung.md",
                "# Tagesordnung\n\n1. Projekt Nord\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        DocumentTextExtractor.Result result = extractor.extract(doc);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("Projekt Nord");
        verify(tika, never()).extract(any(), anyString());
    }

    /** Eine aus Word gespeicherte .txt darf nicht an einem Umlaut scheitern. */
    @Test
    void liestAuchWindowsKodierteTextdatei() throws IOException {
        RecordingDocument doc = document("notiz.txt",
                "Beschluss: Grundstueck in Muenchen".replace("ue", "ü")
                        .getBytes(Charset.forName("windows-1252")));

        DocumentTextExtractor.Result result = extractor.extract(doc);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("München");
    }

    @Test
    void pdfGehtAnTika() throws IOException {
        RecordingDocument doc = document("folien.pdf", new byte[]{1, 2, 3});
        when(tika.extract(any(), anyString()))
                .thenReturn(new TikaClient.ExtractionResult(true, "Folie 1\nProjekt Nord", null));

        DocumentTextExtractor.Result result = extractor.extract(doc);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("Projekt Nord");
        verify(tika).extract(any(), anyString());
    }

    /**
     * Ein Scan ohne OCR liefert eine leere Antwort. Das ist ein Fehler mit
     * Handlungshinweis - sonst waehnt der Nutzer die Unterlage in der Auswertung.
     */
    @Test
    void leererTextGiltAlsFehlerMitHinweisAufOcr() throws IOException {
        RecordingDocument doc = document("scan.pdf", new byte[]{1, 2, 3});
        when(tika.extract(any(), anyString()))
                .thenReturn(new TikaClient.ExtractionResult(true, "   \n\n  ", null));

        DocumentTextExtractor.Result result = extractor.extract(doc);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Kein Text erkannt").contains("OCR");
    }

    @Test
    void gibtDenFehlerVonTikaWeiter() throws IOException {
        RecordingDocument doc = document("folien.pptx", new byte[]{1, 2, 3});
        when(tika.extract(any(), anyString()))
                .thenReturn(TikaClient.ExtractionResult.failed("Tika nicht erreichbar"));

        DocumentTextExtractor.Result result = extractor.extract(doc);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Tika nicht erreichbar");
    }

    @Test
    void weistNichtUnterstuetzteDateitypenAb() throws IOException {
        RecordingDocument doc = document("archiv.zip", new byte[]{1, 2, 3});

        DocumentTextExtractor.Result result = extractor.extract(doc);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("nicht unterstuetzt");
    }

    @Test
    void fehlendeDateiIstEinFehlerUndKeinAbsturz() {
        RecordingDocument doc = RecordingDocument.create(UUID.randomUUID(), "weg.md", null, null);
        doc.setStoredPath(dir.resolve("gibt-es-nicht.md").toString());

        assertThat(extractor.extract(doc).success()).isFalse();
    }

    /** Tika liefert bei PDFs eine Leerzeile je Layout-Umbruch - die kosten nur Platz. */
    @Test
    void zieihtLeerzeilenZusammenUndVereinheitlichtZeilenenden() {
        String normalized = DocumentTextExtractor.normalize("Zeile 1\r\n\r\n\r\n\r\nZeile 2   \n\n\nZeile 3\n\n");

        assertThat(normalized).isEqualTo("Zeile 1\n\nZeile 2\n\nZeile 3");
    }

    @Test
    void erlaubteEndungenUmfassenTextUndTikaFormate() {
        assertThat(DocumentTextExtractor.allowedExtensions()).contains("md", "txt", "pdf", "docx", "png");
        assertThat(DocumentTextExtractor.needsTika("pdf")).isTrue();
        assertThat(DocumentTextExtractor.needsTika("md")).isFalse();
        assertThat(DocumentTextExtractor.isAllowed("exe")).isFalse();
    }
}
