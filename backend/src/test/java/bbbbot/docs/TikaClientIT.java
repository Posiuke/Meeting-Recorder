package bbbbot.docs;

import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prueft die Verstaendigung mit einem <b>echten</b> Tika-Server: Kopfzeilen,
 * Antwortformat und Fehlerfaelle. Die Unit-Tests koennen das nicht - dort ist
 * Tika ein Mock, und genau die Wire-Details waren die offene Frage.
 *
 * <p>Standardmaessig deaktiviert (wie die uebrigen ITs), weil ein Tika-Server
 * noetig ist:
 *
 * <pre>{@code
 * docker run -d --name tika-test -p 9998:9998 apache/tika:latest
 * mvn test -Dtest=TikaClientIT -Dtika.it.url=http://localhost:9998
 * docker rm -f tika-test
 * }</pre>
 *
 * <p><b>OCR</b> ist hier bewusst nicht dabei: Sie haengt daran, ob im Tika-Server
 * tesseract samt Sprachpaket steckt (Image {@code apache/tika:latest-full}), und
 * ein Test, der je nach Image durchfaellt, sagt nichts ueber diesen Code.
 */
@EnabledIfSystemProperty(named = "tika.it.url", matches = ".+")
class TikaClientIT {

    @TempDir
    Path dir;

    private SettingsService settings;
    private TikaClient tika;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        when(settings.get(SettingsService.DOCUMENTS_TIKA_URL))
                .thenReturn(System.getProperty("tika.it.url"));
        when(settings.getInt(SettingsService.DOCUMENTS_TIKA_TIMEOUT_SEC)).thenReturn(60);
        when(settings.get(SettingsService.DOCUMENTS_OCR_STRATEGY)).thenReturn("auto");
        when(settings.get(SettingsService.DOCUMENTS_OCR_LANGUAGE)).thenReturn("deu");
        tika = new TikaClient(settings);
    }

    @Test
    void liestEineTextdateiUndBeweistDamitDieVerstaendigung() throws IOException {
        Path file = dir.resolve("notiz.txt");
        Files.writeString(file, "Beschluss zu Projekt Nord", StandardCharsets.UTF_8);

        TikaClient.ExtractionResult result = tika.extract(file, "notiz.txt");

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("Projekt Nord");
    }

    /** Umlaute im Dateinamen haben in HTTP-Kopfzeilen nichts zu suchen. */
    @Test
    void kommtMitUmlautenImDateinamenZurecht() throws IOException {
        Path file = dir.resolve("uebersicht.txt");
        Files.writeString(file, "Rechenzentrum Nord", StandardCharsets.UTF_8);

        TikaClient.ExtractionResult result = tika.extract(file, "Übersicht Größe.txt");

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("Rechenzentrum Nord");
    }

    /** HTML soll als Text ankommen, nicht als Markup - dafuer ist Tika da. */
    @Test
    void liefertBeiHtmlDenTextOhneMarkup() throws IOException {
        Path file = dir.resolve("einladung.html");
        Files.writeString(file, """
                <html><head><title>Einladung</title></head>
                <body><h1>Jour Fixe</h1><p>Thema: <b>Projekt Nord</b></p></body></html>
                """, StandardCharsets.UTF_8);

        TikaClient.ExtractionResult result = tika.extract(file, "einladung.html");

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("Jour Fixe").contains("Projekt Nord");
        assertThat(result.text()).doesNotContain("<b>");
    }

    /**
     * Der Regelfall: eine PDF mit eingebettetem Text. Prueft zugleich, dass die
     * OCR-Kopfzeilen ({@code X-Tika-PDFOcrStrategy}, {@code X-Tika-OCRLanguage})
     * vom Server angenommen werden - eine unbekannte Kopfzeile waere hier ein 400.
     */
    @Test
    void liestEinePdfMitEingebettetemText() throws IOException {
        Path file = dir.resolve("beschluss.pdf");
        Files.write(file, minimalPdf("Beschluss: Projekt Nord"));

        TikaClient.ExtractionResult result = tika.extract(file, "beschluss.pdf");

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("Projekt Nord");
    }

    /**
     * Ein echtes Office-Format (docx ist ein ZIP mit XML): Das ist der Fall, den
     * dieser Server ohne Tika nicht koennte.
     */
    @Test
    void liestEineDocxDatei() throws IOException {
        Path file = dir.resolve("protokoll.docx");
        writeMinimalDocx(file, "Beschluss: Projekt Nord wird fortgesetzt.");

        TikaClient.ExtractionResult result = tika.extract(file, "protokoll.docx");

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("Projekt Nord");
    }

    /** Eine falsche Adresse muss eine lesbare Meldung ergeben, keinen Absturz. */
    @Test
    void nichtErreichbarerServerErgibtEineVerstaendlicheMeldung() throws IOException {
        when(settings.get(SettingsService.DOCUMENTS_TIKA_URL)).thenReturn("http://127.0.0.1:1");
        Path file = dir.resolve("notiz.txt");
        Files.writeString(file, "Text", StandardCharsets.UTF_8);

        TikaClient.ExtractionResult result = tika.extract(file, "notiz.txt");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("nicht erreichbar");
    }

    @Test
    void verbindungstestLaeuftGegenDenEchtenServer() {
        assertThat(tika.isConfigured()).isTrue();
        assertThat(tika.testConnection().success()).isTrue();
    }

    /**
     * Kleinste gueltige PDF mit einem Textobjekt - selbst gebaut, damit keine
     * Binaerdatei im Repository liegen muss. Aufbau: Katalog, Seitenbaum, Seite,
     * Inhaltsstrom, Schrift, dazu die Querverweistabelle.
     */
    private static byte[] minimalPdf(String text) {
        String stream = "BT /F1 18 Tf 20 100 Td (" + text + ") Tj ET";
        List<String> objects = List.of(
                "<</Type/Catalog/Pages 2 0 R>>",
                "<</Type/Pages/Kids[3 0 R]/Count 1>>",
                "<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 200]/Contents 4 0 R"
                        + "/Resources<</Font<</F1 5 0 R>>>>>>",
                "<</Length " + stream.length() + ">>\nstream\n" + stream + "\nendstream",
                "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>");

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append("%010d 00000 n \n".formatted(offset));
        }
        pdf.append("trailer\n<</Size ").append(objects.size() + 1).append("/Root 1 0 R>>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Kleinste gueltige .docx-Datei: ein ZIP mit Content-Types-Katalog und einem
     * Dokumentteil. Reicht PDFBox/POI in Tika zum Lesen und braucht keine
     * Testdatei im Repository.
     */
    private static void writeMinimalDocx(Path target, String text) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Target="word/document.xml"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"/>
                    </Relationships>
                    """);
            put(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.formatted(text));
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        OutputStream out = zip;
        out.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
