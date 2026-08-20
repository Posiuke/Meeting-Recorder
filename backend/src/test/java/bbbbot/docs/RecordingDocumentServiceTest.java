package bbbbot.docs;

import bbbbot.domain.RecordingDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Unterlagen-Abschnitt des Prompts. Er geht in JEDEN Auswertungsschritt ein -
 * deshalb die Obergrenzen, und deshalb die Ansage, dass Unterlagen kein
 * Gesprochenes sind.
 */
class RecordingDocumentServiceTest {

    private static final int NO_CAP = 0;
    private final UUID recordingId = UUID.randomUUID();

    private RecordingDocument ready(String filename, String text) {
        RecordingDocument doc = RecordingDocument.create(recordingId, filename, null, null);
        doc.setStoredPath("/tmp/" + filename);
        doc.setExtractedText(text);
        doc.setTextChars(text.length());
        doc.setStatus(RecordingDocument.Status.READY);
        return doc;
    }

    private RecordingDocument failed(String filename) {
        RecordingDocument doc = RecordingDocument.create(recordingId, filename, null, null);
        doc.setStoredPath("/tmp/" + filename);
        doc.setStatus(RecordingDocument.Status.FAILED);
        doc.setError("Kein Text erkannt");
        return doc;
    }

    @Test
    void ohneUnterlagenBleibtDerAbschnittWeg() {
        assertThat(RecordingDocumentService.renderPromptBlock(List.of(), NO_CAP, NO_CAP)).isEmpty();
    }

    /** Eine Unterlage ohne Text hat im Prompt nichts zu suchen. */
    @Test
    void nurUnterlagenMitTextGehenEin() {
        String block = RecordingDocumentService.renderPromptBlock(
                List.of(failed("scan.pdf"), ready("tagesordnung.md", "1. Projekt Nord")),
                NO_CAP, NO_CAP);

        assertThat(block).contains("tagesordnung.md").contains("Projekt Nord");
        assertThat(block).doesNotContain("scan.pdf");
    }

    @Test
    void nurGescheiterteUnterlagenErgebenKeinenAbschnitt() {
        assertThat(RecordingDocumentService.renderPromptBlock(
                List.of(failed("scan.pdf")), NO_CAP, NO_CAP)).isEmpty();
    }

    /**
     * Die Ansage im Block ist der Unterschied zwischen "besserer Kontext" und
     * erfundenen Beschluessen aus einer Tagesordnung.
     */
    @Test
    void sagtDemModellDassUnterlagenKeinGesprochenesSind() {
        String block = RecordingDocumentService.renderPromptBlock(
                List.of(ready("tagesordnung.md", "1. Projekt Nord")), NO_CAP, NO_CAP);

        assertThat(block).contains("# Beigefuegte Unterlagen");
        assertThat(block).contains("KEIN Gesprochenes");
        assertThat(block).contains("## tagesordnung.md");
    }

    @Test
    void kuerztJedeUnterlageAufDieEingestellteGrenze() {
        String block = RecordingDocumentService.renderPromptBlock(
                List.of(ready("handbuch.pdf", "Zeile\n".repeat(500))), 200, NO_CAP);

        assertThat(block).contains("[… gekuerzt]");
        // Der Block ist der Rahmentext plus die gekuerzte Unterlage
        assertThat(block.length()).isLessThan(1200);
    }

    /** Ein dickes PDF darf die uebrigen Unterlagen nicht verdraengen. */
    @Test
    void haeltDieGesamtgrenzeEinUndLaesstDenRestWeg() {
        String block = RecordingDocumentService.renderPromptBlock(
                List.of(ready("erste.md", "A".repeat(400)),
                        ready("zweite.md", "B".repeat(400)),
                        ready("dritte.md", "C".repeat(400))),
                NO_CAP, 500);

        assertThat(block).contains("erste.md");
        assertThat(block).doesNotContain("dritte.md");
    }

    /**
     * Auch die erste Unterlage wird gekuerzt, wenn sie allein schon zu gross ist -
     * sonst bliebe der Abschnitt trotz Grenze beliebig lang.
     */
    @Test
    void kuerztAuchDieErsteUnterlageWennSieAlleinZuGrossIst() {
        String block = RecordingDocumentService.renderPromptBlock(
                List.of(ready("handbuch.pdf", "Zeile mit Inhalt\n".repeat(2000))), NO_CAP, 1000);

        assertThat(block).contains("handbuch.pdf");
        assertThat(block).contains("[… gekuerzt]");
        assertThat(block.length()).isLessThan(2500);
    }

    @Test
    void dateinamenWerdenFuerDieAblageEntschaerftUndBehaltenIhreEndung() {
        assertThat(RecordingDocumentService.safeFilename("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(RecordingDocumentService.safeFilename("C:\\Temp\\Angebot Nord.pdf"))
                .isEqualTo("Angebot Nord.pdf");
        assertThat(RecordingDocumentService.safeFilename("Protokoll (Mai).md"))
                .isEqualTo("Protokoll _Mai_.md");
        assertThat(RecordingDocumentService.safeFilename("   ")).startsWith("unterlage");
        assertThat(RecordingDocumentService.safeFilename("a".repeat(400) + ".pdf"))
                .hasSizeLessThanOrEqualTo(RecordingDocument.MAX_FILENAME_LENGTH - 40)
                .endsWith(".pdf");
    }

    @Test
    void endpunktKommtMitBasisUndVollstaendigerAdresseZurecht() {
        assertThat(TikaClient.endpoint("http://tika:9998")).isEqualTo("http://tika:9998/tika");
        assertThat(TikaClient.endpoint("http://tika:9998/")).isEqualTo("http://tika:9998/tika");
        assertThat(TikaClient.endpoint("http://tika:9998/tika")).isEqualTo("http://tika:9998/tika");
    }
}
