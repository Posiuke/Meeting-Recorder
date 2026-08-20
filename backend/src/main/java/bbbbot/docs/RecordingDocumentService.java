package bbbbot.docs;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingDocument;
import bbbbot.repository.Repositories.RecordingDocumentRepo;
import bbbbot.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beigefuegte Unterlagen einer Aufnahme: ablegen, Text daraus holen, in den
 * Auswertungs-Prompt einbauen, loeschen.
 *
 * <p>Die Extraktion laeuft <b>im Hintergrund</b>: OCR eines mehrseitigen Scans
 * dauert Minuten, und so lange darf kein Upload-Aufruf offen stehen. Die Unterlage
 * steht danach auf {@code PENDING} und wird zu {@code READY} oder {@code FAILED} -
 * die Oberflaeche zeigt das an und fragt weiter nach, solange etwas laeuft.
 *
 * <p>Reihum, nicht parallel: Tika (und tesseract dahinter) sind der Engpass, und
 * mehrere gleichzeitige OCR-Laeufe machen niemanden schneller.
 */
@Service
public class RecordingDocumentService {

    private static final Logger log = LoggerFactory.getLogger(RecordingDocumentService.class);

    /** Unterverzeichnis der Aufnahme, in dem die Originaldateien liegen. */
    static final String SUBDIRECTORY = "documents";

    /** Missbrauchsgrenze - fachlich braucht eine Besprechung keine 50 Unterlagen. */
    public static final int MAX_DOCUMENTS_PER_RECORDING = 20;

    private final RecordingDocumentRepo repo;
    private final DocumentTextExtractor extractor;
    private final SettingsService settings;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "document-extract");
        t.setDaemon(true);
        return t;
    });

    public RecordingDocumentService(RecordingDocumentRepo repo, DocumentTextExtractor extractor,
                                    SettingsService settings) {
        this.repo = repo;
        this.extractor = extractor;
        this.settings = settings;
    }

    public boolean isEnabled() {
        return settings.getBool(SettingsService.DOCUMENTS_ENABLED);
    }

    /** Ist ein Tika-Server eingerichtet? Ohne ihn gehen nur Text- und Markdown-Dateien. */
    public boolean tikaConfigured() {
        return extractor.tikaConfigured();
    }

    public List<RecordingDocument> documentsOf(UUID recordingId) {
        return repo.findByRecordingIdOrderByCreatedAtAsc(recordingId);
    }

    public long countOf(UUID recordingId) {
        return repo.countByRecordingId(recordingId);
    }

    public RecordingDocument require(UUID recordingId, UUID documentId) {
        return repo.findById(documentId)
                .filter(d -> d.getRecordingId().equals(recordingId))
                .orElseThrow(() -> new IllegalArgumentException("Unterlage nicht gefunden"));
    }

    /** Obergrenze fuer eine einzelne Unterlage in Byte (Admin-Einstellung). */
    public long maxBytes() {
        return Math.max(1, settings.getLong(SettingsService.DOCUMENTS_MAX_MEGABYTES)) * 1024L * 1024L;
    }

    /**
     * Legt eine Unterlage neben die Aufnahme und stoesst die Textextraktion an.
     * Die Datei behaelt ihren Namen (mit vorangestellter Kennung, damit zwei
     * gleichnamige Dateien sich nicht ueberschreiben).
     */
    public RecordingDocument add(Recording recording, String originalFilename, String contentType,
                                InputStream data, UUID uploadedBy) throws IOException {
        Path dir = Path.of(recording.getDirectory()).resolve(SUBDIRECTORY);
        Files.createDirectories(dir);

        String filename = safeFilename(originalFilename);
        RecordingDocument document = RecordingDocument.create(recording.getId(), filename,
                contentType, uploadedBy);
        // Kennung der Zeile im Dateinamen: So ueberschreiben sich zwei gleichnamige
        // Dateien nicht, und im Verzeichnis ist sichtbar, was zu welcher Zeile gehoert.
        Path target = dir.resolve(document.getId() + "_" + filename);
        long bytes = Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        document.setStoredPath(target.toAbsolutePath().toString());
        document.setSizeBytes(bytes);
        repo.save(document);
        log.info("Unterlage '{}' ({} Byte) zu Aufnahme {} hinzugefuegt", filename, bytes, recording.getId());
        queueExtraction(document.getId());
        return document;
    }

    /**
     * Extraktion (erneut) anstossen - z.B. nachdem der Admin den Tika-Server
     * eingerichtet hat. Eine gescheiterte Unterlage muss dafuer nicht neu
     * hochgeladen werden.
     */
    public RecordingDocument retry(RecordingDocument document) {
        document.setStatus(RecordingDocument.Status.PENDING);
        document.setError(null);
        repo.save(document);
        queueExtraction(document.getId());
        return document;
    }

    /** Datei und Zeile entfernen. Ein fehlendes Original ist kein Fehler mehr. */
    public void delete(RecordingDocument document) {
        try {
            Files.deleteIfExists(Path.of(document.getStoredPath()));
        } catch (IOException e) {
            log.warn("Unterlage {} konnte nicht geloescht werden: {}",
                    document.getStoredPath(), e.getMessage());
        }
        repo.delete(document);
    }

    /** Wartet noch eine Unterlage auf ihren Text? (Die Oberflaeche fragt dann weiter nach.) */
    public boolean hasPending(UUID recordingId) {
        return documentsOf(recordingId).stream()
                .anyMatch(d -> d.getStatus() == RecordingDocument.Status.PENDING);
    }

    private void queueExtraction(UUID documentId) {
        worker.submit(() -> {
            try {
                extractNow(documentId);
            } catch (RuntimeException e) {
                log.error("Textextraktion fuer Unterlage {} unerwartet gescheitert", documentId, e);
            }
        });
    }

    /** Der eigentliche Lauf; auch vom Start-Recovery fuer haengende Unterlagen genutzt. */
    void extractNow(UUID documentId) {
        RecordingDocument document = repo.findById(documentId).orElse(null);
        if (document == null) return;
        DocumentTextExtractor.Result result = extractor.extract(document);
        document.setExtractedAt(Instant.now());
        if (result.success()) {
            document.setExtractedText(result.text());
            document.setTextChars(result.text().length());
            document.setStatus(RecordingDocument.Status.READY);
            document.setError(null);
        } else {
            document.setExtractedText(null);
            document.setTextChars(null);
            document.setStatus(RecordingDocument.Status.FAILED);
            document.setError(result.error());
            log.warn("Unterlage '{}' (Aufnahme {}): {}", document.getFilename(),
                    document.getRecordingId(), result.error());
        }
        repo.save(document);
    }

    /**
     * Unterlagen-Abschnitt fuer den Auswertungs-Prompt. Leer, wenn es keine
     * verwertbare Unterlage gibt oder der Admin die Funktion abgeschaltet hat -
     * dann entfaellt der Abschnitt ganz.
     *
     * <p>Der Block enthaelt bewusst die Ansage, dass Unterlagen <b>kein
     * Gesprochenes</b> sind: Ohne sie zieht das Modell Beschluesse aus einer
     * Tagesordnung, die in der Besprechung nie gefallen sind.
     */
    public String promptBlock(UUID recordingId) {
        if (!isEnabled()) return "";
        return renderPromptBlock(documentsOf(recordingId),
                settings.getInt(SettingsService.DOCUMENTS_MAX_CHARS_PER_DOCUMENT),
                settings.getInt(SettingsService.DOCUMENTS_PROMPT_MAX_CHARS));
    }

    /**
     * @param maxCharsPerDocument Zeichen je Unterlage; {@code <= 0} = unbegrenzt.
     *                            Ein dickes PDF soll die uebrigen nicht verdraengen.
     * @param maxCharsTotal       Obergrenze fuer den ganzen Block; {@code <= 0} = unbegrenzt.
     *                            Er geht in JEDEN Auswertungsschritt ein.
     */
    static String renderPromptBlock(List<RecordingDocument> documents, int maxCharsPerDocument,
                                    int maxCharsTotal) {
        List<RecordingDocument> usable = documents.stream().filter(RecordingDocument::isUsable).toList();
        if (usable.isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        int used = 0;
        for (RecordingDocument document : usable) {
            String text = shorten(document.getExtractedText(), maxCharsPerDocument);
            String part = "## " + document.getFilename() + "\n" + text + "\n\n";
            if (maxCharsTotal > 0 && used + part.length() > maxCharsTotal) {
                // Zwischen Unterlagen abschneiden, nicht mitten in einer: Ein halber
                // Satz aus einem Papier ist schlechter als das Papier weglassen.
                if (parts.isEmpty()) {
                    part = "## " + document.getFilename() + "\n"
                            + shorten(text, Math.max(200, maxCharsTotal - 200)) + "\n\n";
                    parts.add(part);
                }
                break;
            }
            parts.add(part);
            used += part.length();
        }

        return """
                # Beigefuegte Unterlagen
                Diese Unterlagen wurden der Aufnahme beigefuegt (z.B. Tagesordnung, Folien, Papiere). \
                Sie sind Hintergrund und KEIN Gesprochenes: Nutze sie, um Themen, Namen, Abkuerzungen \
                und Zahlen richtig einzuordnen. Leite daraus keine Beschluesse, Aufgaben oder Aussagen \
                ab - berichte nur, was im Transkript oder Chat vorkommt.

                """ + String.join("", parts);
    }

    /** Kuerzt an einer Zeilengrenze, damit kein halber Satz stehen bleibt. */
    private static String shorten(String text, int maxChars) {
        if (text == null) return "";
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        String cut = text.substring(0, maxChars);
        int newline = cut.lastIndexOf('\n');
        if (newline > maxChars / 2) cut = cut.substring(0, newline);
        return cut.strip() + "\n[… gekuerzt]";
    }

    /**
     * Dateiname auf unproblematische Zeichen beschraenken (er wird Teil eines
     * Pfades) und auf Spaltenlaenge kuerzen - die Endung bleibt erhalten, weil sie
     * ueber den Extraktionsweg entscheidet.
     */
    static String safeFilename(String original) {
        String name = original == null ? "" : original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("\\s+", " ").trim();
        if (name.isBlank() || name.startsWith(".")) name = "unterlage" + name;
        int limit = RecordingDocument.MAX_FILENAME_LENGTH - 40; // Platz fuer die Kennung im Dateinamen
        if (name.length() > limit) {
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
            name = name.substring(0, limit - ext.length()) + ext;
        }
        return name;
    }
}
