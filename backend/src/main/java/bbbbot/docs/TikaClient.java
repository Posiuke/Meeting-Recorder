package bbbbot.docs;

import bbbbot.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Textextraktion ueber einen Apache-Tika-Server ({@code documents.tikaUrl}).
 * Zustaendig fuer alles, was nicht schon Text ist: PDF, Office-Dateien, HTML,
 * E-Mails und Bilder.
 *
 * <p><b>OCR</b> macht Tika, nicht dieser Server: Ist im Tika-Container tesseract
 * installiert, liest er auch gescannte PDFs und Fotos. Wie stark er es versucht,
 * steuert {@code documents.ocrStrategy} ({@code auto} = nur wenn kaum Text
 * eingebettet ist) samt Sprache {@code documents.ocrLanguage}; beides geht als
 * Kopfzeile mit, sodass die Tika-Konfiguration nicht angefasst werden muss.
 *
 * <p>Angesprochen wird {@code PUT /tika} mit {@code Accept: text/plain} - der
 * schlichteste Weg, der reinen Text zurueckgibt. Der Dateiinhalt geht als
 * {@code application/octet-stream} raus, damit Tika den Typ selbst erkennt (ein
 * falscher Content-Type vom Browser wuerde ihn auf den falschen Parser schicken);
 * der Dateiname geht als Hinweis mit.
 */
@Service
public class TikaClient {

    private static final Logger log = LoggerFactory.getLogger(TikaClient.class);

    /** Mehr als das kann keine Fehlermeldung sein - der Rest hilft nicht weiter. */
    private static final int MAX_ERROR_CHARS = 500;

    private final SettingsService settings;
    private volatile HttpClient httpClient;

    public TikaClient(SettingsService settings) {
        this.settings = settings;
    }

    /**
     * @param success true = Tika hat geantwortet. Ein leerer Text ist KEIN Fehler
     *                dieses Clients - ob er verwertbar ist, entscheidet der Aufrufer
     *                (siehe {@link DocumentTextExtractor}).
     */
    public record ExtractionResult(boolean success, String text, String error) {
        static ExtractionResult failed(String error) {
            return new ExtractionResult(false, null, error);
        }
    }

    /** Ist ein Tika-Server eingerichtet? Ohne ihn gehen nur Text- und Markdown-Dateien. */
    public boolean isConfigured() {
        return !settings.get(SettingsService.DOCUMENTS_TIKA_URL).isBlank();
    }

    public ExtractionResult extract(Path file, String filename) {
        String base = settings.get(SettingsService.DOCUMENTS_TIKA_URL).trim();
        if (base.isEmpty()) {
            return ExtractionResult.failed("Kein Tika-Server eingerichtet (Einstellung documents.tikaUrl) - "
                    + "ohne ihn lassen sich nur Text- und Markdown-Dateien auswerten");
        }
        int timeoutSec = Math.max(5, settings.getInt(SettingsService.DOCUMENTS_TIKA_TIMEOUT_SEC));
        String url = endpoint(base);
        long begin = System.nanoTime();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Accept", "text/plain; charset=UTF-8")
                    .header("Content-Type", "application/octet-stream")
                    // Dateiname als Erkennungshilfe - Tika liest ihn aus dieser Kopfzeile
                    .header("Content-Disposition", "attachment; filename=\"" + asciiFilename(filename) + "\"")
                    .header("X-Tika-PDFOcrStrategy", settings.get(SettingsService.DOCUMENTS_OCR_STRATEGY).trim())
                    .PUT(HttpRequest.BodyPublishers.ofFile(file));
            String ocrLanguage = settings.get(SettingsService.DOCUMENTS_OCR_LANGUAGE).trim();
            if (!ocrLanguage.isEmpty()) {
                request.header("X-Tika-OCRLanguage", ocrLanguage);
            }
            HttpResponse<String> response = client().send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = (System.nanoTime() - begin) / 1_000_000;
            if (response.statusCode() == 200) {
                String text = response.body() == null ? "" : response.body();
                log.info("Tika: '{}' in {} ms gelesen ({} Zeichen)", filename, ms, text.strip().length());
                return new ExtractionResult(true, text, null);
            }
            if (response.statusCode() == 422) {
                return ExtractionResult.failed("Tika kann diese Datei nicht lesen "
                        + "(beschaedigt oder passwortgeschuetzt?)");
            }
            return ExtractionResult.failed("Tika HTTP " + response.statusCode() + ": "
                    + truncate(response.body()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return ExtractionResult.failed("Unterbrochen");
            }
            long seconds = (System.nanoTime() - begin) / 1_000_000_000;
            // ConnectException & Co. haben oft keine Message; Klassenname, URL und
            // Dauer trennen "nicht erreichbar" von "OCR laeuft in ein Zeitlimit".
            return ExtractionResult.failed("Tika nicht erreichbar (" + url + ") nach " + seconds + " s: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Endpunkt aus der eingestellten Basis-URL. Beides ist erlaubt und gemeint:
     * {@code http://tika:9998} und {@code http://tika:9998/tika} - ein Admin, der
     * die vollstaendige Adresse eintraegt, soll nicht auf {@code /tika/tika} laufen.
     */
    static String endpoint(String base) {
        String cleaned = base.trim().replaceAll("/+$", "");
        return cleaned.endsWith("/tika") ? cleaned : cleaned + "/tika";
    }

    /**
     * HTTP-Kopfzeilen duerfen nur ASCII enthalten - Umlaute im Dateinamen wuerden
     * die Anfrage sonst scheitern lassen. Der Name ist hier nur Erkennungshilfe,
     * die Endung zaehlt.
     */
    private static String asciiFilename(String filename) {
        String cleaned = (filename == null ? "" : filename).replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "unterlage" : cleaned;
    }

    /** Verbindungstest fuer die Admin-Oberflaeche: liest eine winzige Textdatei. */
    public ExtractionResult testConnection() {
        Path probe = null;
        try {
            probe = Files.createTempFile("tika-connection-test", ".txt");
            Files.writeString(probe, "Verbindungstest", StandardCharsets.UTF_8);
            return extract(probe, "verbindungstest.txt");
        } catch (IOException e) {
            return ExtractionResult.failed("Testdatei konnte nicht angelegt werden: " + e.getMessage());
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // Aufraeumen ist Nebensache - das Testergebnis zaehlt
                }
            }
        }
    }

    private static String truncate(String body) {
        if (body == null) return "";
        String cleaned = body.strip();
        return cleaned.length() <= MAX_ERROR_CHARS ? cleaned : cleaned.substring(0, MAX_ERROR_CHARS) + "…";
    }

    /**
     * Ein Client fuer alle Anfragen. HTTP/1.1 wie beim LLM-Client: Der
     * Upgrade-Versuch von HTTP/2 bringt bei diesen Diensten nichts und hat dort
     * schon Anfragen gekostet.
     */
    private HttpClient client() {
        HttpClient existing = httpClient;
        if (existing != null) return existing;
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(15))
                        .build();
            }
            return httpClient;
        }
    }
}
