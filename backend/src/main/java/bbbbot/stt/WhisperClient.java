package bbbbot.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import bbbbot.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * STT-Client mit zwei Anbietern (Setting whisper.provider):
 *
 * "local": Whisper-ASR-Webservice im Intranet (onerahmet/openai-whisper-asr-webservice).
 * Laedt MP3-Segmente als multipart/form-data auf /asr hoch. Alle Parameter
 * (Sprache, VAD, Output-Format, initial_prompt) kommen aus den Admin-Einstellungen.
 *
 * "openai": OpenAI-kompatible Cloud-API (POST /v1/audio/transcriptions mit
 * Bearer-Token) - funktioniert mit OpenAI selbst und mit kompatiblen Anbietern
 * wie Groq oder Mistral. Sprechererkennung (Diarisierung) wird dort nicht
 * unterstuetzt und faellt still weg.
 *
 * output=json bzw. verbose_json liefert Segmente mit Zeitstempeln (und bei
 * WhisperX-Engine auch Sprecher-Labels) - daraus wird ein lesbares Transkript gebaut.
 */
@Service
public class WhisperClient {

    private static final Logger log = LoggerFactory.getLogger(WhisperClient.class);

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public WhisperClient(SettingsService settings) {
        this.settings = settings;
    }

    public record TranscriptionResult(boolean success, String text, String error) {}

    /**
     * @param diarize Sprechererkennung fuer diese Datei anfordern (benoetigt
     *                ASR_ENGINE=whisperx auf dem Whisper-Server).
     */
    public TranscriptionResult transcribe(Path audioFile, boolean diarize) {
        int attempts = Math.max(1, settings.getInt(SettingsService.WHISPER_RETRY_ATTEMPTS));
        long baseMs = settings.getLong(SettingsService.WHISPER_RETRY_BASE_MS);
        TranscriptionResult result = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            result = attemptTranscribe(audioFile, diarize);
            if (result.success()) return result;
            if (attempt < attempts) {
                long wait = baseMs * (1L << (attempt - 1));
                log.warn("Whisper-Versuch {}/{} fuer {} fehlgeschlagen ({}) - erneut in {} ms",
                        attempt, attempts, audioFile.getFileName(), result.error(), wait);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
        return result;
    }

    private TranscriptionResult attemptTranscribe(Path audioFile, boolean diarize) {
        if ("openai".equalsIgnoreCase(settings.get(SettingsService.WHISPER_PROVIDER))) {
            return attemptTranscribeOpenAi(audioFile, diarize);
        }
        return attemptTranscribeLocal(audioFile, diarize);
    }

    /**
     * OpenAI-kompatible Audio-API: multipart mit file/model/response_format.
     * verbose_json liefert Segmente mit Zeitstempeln; Modelle, die nur "json"
     * koennen (z.B. gpt-4o-transcribe), bekommen automatisch einen zweiten
     * Versuch ohne Zeitstempel.
     */
    private TranscriptionResult attemptTranscribeOpenAi(Path audioFile, boolean diarize) {
        String url = settings.get(SettingsService.WHISPER_OPENAI_URL);
        String apiKey = settings.get(SettingsService.WHISPER_OPENAI_API_KEY);
        String model = settings.get(SettingsService.WHISPER_OPENAI_MODEL);
        String language = settings.get(SettingsService.WHISPER_LANGUAGE);
        String initialPrompt = settings.get(SettingsService.WHISPER_INITIAL_PROMPT);
        int timeoutSec = settings.getInt(SettingsService.WHISPER_TIMEOUT_SEC);

        if (apiKey.isBlank()) {
            return new TranscriptionResult(false, null,
                    "Kein API-Key fuer die Cloud-Spracherkennung hinterlegt (Einstellung whisper.openaiApiKey)");
        }
        if (diarize) {
            log.info("Sprechererkennung wird von der OpenAI-Audio-API nicht unterstuetzt - Transkript ohne Sprecher-Labels");
        }

        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("model", model);
        fields.put("response_format", "verbose_json");
        if (!language.isBlank()) fields.put("language", language);
        if (!initialPrompt.isBlank()) fields.put("prompt", initialPrompt);

        TranscriptionResult result = postMultipart(url, apiKey, "file", audioFile, fields, timeoutSec);
        if (!result.success() && result.error() != null && result.error().contains("response_format")) {
            log.info("Modell {} unterstuetzt verbose_json nicht - zweiter Versuch mit response_format=json", model);
            fields.put("response_format", "json");
            result = postMultipart(url, apiKey, "file", audioFile, fields, timeoutSec);
        }
        return result;
    }

    private TranscriptionResult attemptTranscribeLocal(Path audioFile, boolean diarize) {
        String baseUrl = settings.get(SettingsService.WHISPER_URL);
        String language = settings.get(SettingsService.WHISPER_LANGUAGE);
        String output = settings.get(SettingsService.WHISPER_OUTPUT);
        boolean vad = settings.getBool(SettingsService.WHISPER_VAD_FILTER);
        String initialPrompt = settings.get(SettingsService.WHISPER_INITIAL_PROMPT);
        int timeoutSec = settings.getInt(SettingsService.WHISPER_TIMEOUT_SEC);

        StringBuilder query = new StringBuilder();
        appendParam(query, "task", "transcribe");
        appendParam(query, "language", language);
        appendParam(query, "output", output);
        appendParam(query, "vad_filter", String.valueOf(vad));
        if (diarize) {
            appendParam(query, "diarize", "true");
        }
        if (!initialPrompt.isBlank()) {
            appendParam(query, "initial_prompt", initialPrompt);
        }
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + query;
        return postMultipart(url, null, "audio_file", audioFile, Map.of(), timeoutSec);
    }

    /**
     * Multipart-Upload an einen STT-Endpunkt. Erfolgreiche Antworten werden
     * durch {@link #renderJsonTranscript} in ein lesbares Transkript umgesetzt
     * (Nicht-JSON-Antworten bleiben unveraendert erhalten).
     */
    private TranscriptionResult postMultipart(String url, String bearerToken, String fileField,
                                              Path audioFile, Map<String, String> textFields, int timeoutSec) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    // HTTP/1.1 erzwingen: der Whisper-Server kommt mit dem HTTP/2-
                    // (h2c-)Upgrade + chunked-Body nicht klar (Multipart-Feld ging verloren).
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            String boundary = "----bbbbot" + UUID.randomUUID();
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(multipartBody(boundary, fileField, audioFile, textFields));
            if (bearerToken != null && !bearerToken.isBlank()) {
                request.header("Authorization", "Bearer " + bearerToken);
            }

            log.info("Sende {} an STT-Endpunkt {}", audioFile.getFileName(), url);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new TranscriptionResult(false, null,
                        "STT HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));
            }
            return new TranscriptionResult(true, renderJsonTranscript(response.body()), null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // ConnectException & Co. haben oft keine Message - Klassenname und URL helfen bei der Diagnose
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new TranscriptionResult(false, null,
                    "STT-Endpunkt nicht erreichbar (" + url + "): " + reason);
        }
    }

    /**
     * Baut aus der Whisper-JSON-Antwort ein lesbares Transkript mit
     * [mm:ss]-Zeitstempeln und - falls vorhanden (WhisperX-Diarisierung) -
     * Sprecher-Labels.
     */
    String renderJsonTranscript(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode segments = root.get("segments");
            if (segments == null || !segments.isArray() || segments.isEmpty()) {
                JsonNode text = root.get("text");
                return text != null ? text.asText().trim() : json.trim();
            }
            StringBuilder sb = new StringBuilder();
            String lastSpeaker = null;
            for (JsonNode seg : segments) {
                String text = seg.path("text").asText("").trim();
                if (text.isEmpty()) continue;
                double start = seg.path("start").asDouble(0);
                String speaker = seg.hasNonNull("speaker") ? seg.get("speaker").asText() : null;
                if (speaker != null && !speaker.equals(lastSpeaker)) {
                    sb.append('\n').append(speaker).append(":\n");
                    lastSpeaker = speaker;
                }
                sb.append("[").append(formatTime(start)).append("] ").append(text).append('\n');
            }
            return sb.toString().trim();
        } catch (IOException e) {
            log.warn("Whisper-JSON konnte nicht geparst werden, verwende Rohtext.");
            return json.trim();
        }
    }

    private static String formatTime(double seconds) {
        long total = (long) seconds;
        return "%02d:%02d".formatted(total / 60, total % 60);
    }

    private static void appendParam(StringBuilder query, String key, String value) {
        if (!query.isEmpty()) query.append('&');
        query.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static HttpRequest.BodyPublisher multipartBody(String boundary, String fieldName, Path file,
                                                           Map<String, String> textFields) throws IOException {
        // Wichtig: als EIN byte[] mit bekannter Laenge senden. ofByteArrays() meldet
        // Laenge -1 -> HttpClient nutzt chunked Transfer-Encoding, was der Whisper-
        // Server nicht korrekt parst (audio_file fehlt -> HTTP 422). ofByteArray()
        // setzt dagegen Content-Length.
        var out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> field : textFields.entrySet()) {
            out.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
                + file.getFileName() + "\"\r\n"
                + "Content-Type: audio/mpeg\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(file));
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArray(out.toByteArray());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
