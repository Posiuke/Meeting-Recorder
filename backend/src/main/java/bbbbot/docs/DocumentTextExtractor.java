package bbbbot.docs;

import bbbbot.domain.RecordingDocument;
import bbbbot.llm.GlossaryCsv;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Holt den Text aus einer beigefuegten Unterlage. Zwei Wege, nach Dateiendung:
 *
 * <ul>
 *   <li><b>Text und Markdown</b> liest dieser Server selbst - fuer eine .md-Datei
 *       einen externen Dienst zu brauchen waere unangemessen.</li>
 *   <li><b>Alles andere</b> (PDF, Office, HTML, E-Mail, Bilder) geht an den
 *       Tika-Server. Dort passiert auch die <b>OCR</b> von Scans, sofern tesseract
 *       installiert ist. Ohne eingerichteten Tika-Server scheitert die Extraktion
 *       mit klarer Meldung - lieber das als eine Unterlage, die stillschweigend
 *       leer in der Auswertung landet.</li>
 * </ul>
 *
 * <p>Die Endungen sind eine Positivliste: Sie entscheidet, was ueberhaupt
 * beigefuegt werden darf, und liefert dem Nutzer eine verstaendliche Meldung statt
 * eines Tika-Fehlers zu einer .zip.
 */
@Service
public class DocumentTextExtractor {

    /** Direkt lesbare Textformate - kein externer Dienst noetig. */
    public static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
            "txt", "text", "md", "markdown", "csv", "tsv", "log", "json", "yaml", "yml",
            "adoc", "rst");

    /**
     * Formate, die der Tika-Server liest. Bilder sind dabei, weil ein Foto der
     * Tafel oder ein eingescanntes Papier genau der Fall ist, um den es geht.
     */
    public static final Set<String> TIKA_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "rtf", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp",
            "html", "htm", "xml", "epub", "msg", "eml",
            "png", "jpg", "jpeg", "tif", "tiff", "bmp", "webp", "gif", "heic");

    /**
     * Obergrenze fuer den gespeicherten Text. Wie viel davon in den Prompt geht,
     * legt der Admin fest ({@code documents.maxCharsPerDocument}); gespeichert wird
     * grosszuegig mehr, damit ein spaeter erhoehtes Limit nicht eine neue
     * Extraktion (mit OCR!) braucht. Die Grenze ist nur die Notbremse gegen ein
     * 400-Seiten-Handbuch in einer Textspalte.
     */
    static final int MAX_STORED_CHARS = 1_000_000;

    private final TikaClient tika;

    public DocumentTextExtractor(TikaClient tika) {
        this.tika = tika;
    }

    /**
     * @param text  extrahierter Text (bei Erfolg), auf {@link #MAX_STORED_CHARS} gekuerzt
     * @param error Grund des Scheiterns - er landet unveraendert in der Anzeige
     */
    public record Result(boolean success, String text, String error) {
        static Result failed(String error) {
            return new Result(false, null, error);
        }
    }

    /** Alle erlaubten Endungen, alphabetisch - fuer Meldungen und die Oberflaeche. */
    public static Set<String> allowedExtensions() {
        Set<String> all = new TreeSet<>(PLAIN_TEXT_EXTENSIONS);
        all.addAll(TIKA_EXTENSIONS);
        return all;
    }

    public static boolean isAllowed(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return PLAIN_TEXT_EXTENSIONS.contains(ext) || TIKA_EXTENSIONS.contains(ext);
    }

    /** Braucht diese Endung den Tika-Server? */
    public static boolean needsTika(String extension) {
        return TIKA_EXTENSIONS.contains(extension == null ? "" : extension.toLowerCase(Locale.ROOT));
    }

    /** Ist ein Tika-Server eingerichtet? Ohne ihn gehen nur Text- und Markdown-Dateien. */
    public boolean tikaConfigured() {
        return tika.isConfigured();
    }

    public Result extract(RecordingDocument document) {
        Path file = Path.of(document.getStoredPath());
        if (!Files.isReadable(file)) {
            return Result.failed("Datei nicht gefunden: " + document.getFilename());
        }
        String extension = document.extension();
        if (!isAllowed(extension)) {
            return Result.failed("Dateityp nicht unterstuetzt: ." + extension);
        }

        if (PLAIN_TEXT_EXTENSIONS.contains(extension)) {
            try {
                // Wie beim Glossar-Import: UTF-8, und nur wenn die Bytes darin nicht
                // aufgehen, Windows-1252 - eine aus Word gespeicherte .txt darf nicht
                // an einem Umlaut scheitern.
                return usable(GlossaryCsv.decode(Files.readAllBytes(file)));
            } catch (IOException e) {
                return Result.failed("Datei konnte nicht gelesen werden: " + e.getMessage());
            }
        }

        TikaClient.ExtractionResult result = tika.extract(file, document.getFilename());
        if (!result.success()) return Result.failed(result.error());
        return usable(result.text());
    }

    /**
     * Kein Text heisst gescheitert: Ein Scan ohne OCR liefert eine leere Antwort,
     * und das muss sichtbar sein - sonst waehnt der Nutzer die Unterlage in der
     * Auswertung, wo nichts von ihr ankommt.
     */
    private Result usable(String raw) {
        String text = normalize(raw);
        if (text.isBlank()) {
            return Result.failed("Kein Text erkannt. Bei einem Scan oder Foto: Ist im Tika-Server "
                    + "OCR (tesseract) eingerichtet und documents.ocrStrategy passend gesetzt?");
        }
        return new Result(true, text, null);
    }

    /**
     * Tika liefert bei PDFs viele Leerzeilen (eine je Layout-Umbruch). Sie kosten
     * im Prompt Platz und sagen nichts - drei oder mehr werden zu einer
     * Absatzgrenze zusammengezogen, Zeilenenden vereinheitlicht.
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
        return text.length() > MAX_STORED_CHARS ? text.substring(0, MAX_STORED_CHARS) : text;
    }
}
