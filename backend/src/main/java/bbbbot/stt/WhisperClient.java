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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * STT-Client mit zwei Anbietern (Setting whisper.provider):
 *
 * "local": Whisper-ASR-Webservice im Intranet (onerahmet/openai-whisper-asr-webservice).
 * Laedt MP3-Segmente als multipart/form-data auf /asr hoch. Die Parameter (VAD,
 * Output-Format, initial_prompt) kommen aus den Admin-Einstellungen; die Sprache
 * darf die Aufnahme vorgeben (siehe {@link SttLanguage}).
 *
 * "openai": OpenAI-kompatible Cloud-API (POST /v1/audio/transcriptions mit
 * Bearer-Token) - funktioniert mit OpenAI selbst und mit kompatiblen Anbietern
 * wie Groq oder Mistral. Mit Sprechererkennung geht die Datei an ein eigenes
 * Diarisierungs-Modell (whisper.openaiDiarizeModel, response_format
 * diarized_json); siehe {@link SpeakerContext} fuer stabile Sprecher ueber
 * mehrere Segmente.
 *
 * output=json bzw. verbose_json liefert Segmente mit Zeitstempeln (und bei
 * WhisperX-Engine auch Sprecher-Labels) - daraus wird ein lesbares Transkript gebaut.
 */
@Service
public class WhisperClient {

    private static final Logger log = LoggerFactory.getLogger(WhisperClient.class);

    /** Hoechstzahl der Stimmreferenzen, die die Cloud je Anfrage annimmt. */
    static final int MAX_SPEAKER_REFERENCES = 4;
    /** Laenge einer Stimmreferenz: die API verlangt 2 bis 10 Sekunden. */
    private static final double MIN_REFERENCE_SECONDS = 2.0;
    private static final double MAX_REFERENCE_SECONDS = 10.0;

    private final SettingsService settings;
    private final ClipExtractor clipExtractor;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Schneidet einen Audio-Ausschnitt als WAV heraus (ffmpeg); null = fehlgeschlagen. */
    @FunctionalInterface
    public interface ClipExtractor {
        Path extract(Path source, double startSeconds, double durationSeconds, Path wav);
    }

    /** Ohne Ausschnitt-Werkzeug: Cloud-Diarisierung ohne Stimmreferenzen (Tests). */
    public WhisperClient(SettingsService settings) {
        this(settings, (ClipExtractor) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WhisperClient(SettingsService settings, bbbbot.media.FfmpegService ffmpeg) {
        this(settings, ffmpeg::extractWavClip);
    }

    WhisperClient(SettingsService settings, ClipExtractor clipExtractor) {
        this.settings = settings;
        this.clipExtractor = clipExtractor;
    }

    public record TranscriptionResult(boolean success, String text, String error) {}

    /**
     * Sprecher-Gedaechtnis ueber die Segmente EINER Aufnahme. Die Cloud
     * diarisiert jede Datei fuer sich und nennt die Sprecher dort A, B, C - in
     * jedem Segment neu. Damit "Sprecher 1" in Segment 3 dieselbe Person ist
     * wie in Segment 1, werden aus den ersten Segmenten kurze Stimmproben je
     * Sprecher geschnitten und den folgenden Anfragen als bekannte Sprecher
     * (known_speaker_names/-references) mitgegeben. Neue Stimmen bekommen
     * fortlaufende Labels SPEAKER_00, SPEAKER_01, ...
     */
    public static final class SpeakerContext {
        private final Map<String, String> references = new LinkedHashMap<>();
        private int nextIndex;

        String nextLabel() {
            return "SPEAKER_%02d".formatted(nextIndex++);
        }

        int referenceCount() {
            return references.size();
        }
    }

    /**
     * @param diarize Sprechererkennung fuer diese Datei anfordern (benoetigt
     *                ASR_ENGINE=whisperx auf dem Whisper-Server).
     */
    public TranscriptionResult transcribe(Path audioFile, boolean diarize) {
        return transcribe(audioFile, diarize, null);
    }

    /**
     * @param diarize  Sprechererkennung fuer diese Datei anfordern (benoetigt
     *                 ASR_ENGINE=whisperx auf dem Whisper-Server).
     * @param language Sprache dieser Aufnahme: {@code null}/leer = Admin-Standard
     *                 ({@code whisper.language}), {@link SttLanguage#AUTO} =
     *                 keine Vorgabe, Whisper erkennt die Sprache selbst.
     */
    public TranscriptionResult transcribe(Path audioFile, boolean diarize, String language) {
        return transcribe(audioFile, diarize, language, null);
    }

    /**
     * Wie {@link #transcribe(Path, boolean, String)}; {@code speakers} haelt die
     * Sprecher ueber alle Segmente einer Aufnahme stabil (Cloud-Diarisierung).
     * Segmente in Reihenfolge uebergeben; null = jedes Segment fuer sich.
     */
    public TranscriptionResult transcribe(Path audioFile, boolean diarize, String language,
                                          SpeakerContext speakers) {
        int attempts = Math.max(1, settings.getInt(SettingsService.WHISPER_RETRY_ATTEMPTS));
        long baseMs = settings.getLong(SettingsService.WHISPER_RETRY_BASE_MS);
        String effectiveLanguage = resolveLanguage(language);
        TranscriptionResult result = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            result = attemptTranscribe(audioFile, diarize, effectiveLanguage, speakers);
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

    /**
     * Sprachvorgabe fuer diese Anfrage: Die Wahl an der Aufnahme geht vor, ohne
     * Wahl gilt der Admin-Standard. "Automatisch erkennen" wird zur leeren
     * Vorgabe - der Parameter entfaellt dann in der Anfrage und Whisper
     * entscheidet selbst.
     */
    private String resolveLanguage(String recordingLanguage) {
        String language = recordingLanguage == null || recordingLanguage.isBlank()
                ? settings.get(SettingsService.WHISPER_LANGUAGE)
                : recordingLanguage.trim();
        return SttLanguage.AUTO.equalsIgnoreCase(language) ? "" : language;
    }

    private TranscriptionResult attemptTranscribe(Path audioFile, boolean diarize, String language,
                                                  SpeakerContext speakers) {
        if ("openai".equalsIgnoreCase(settings.get(SettingsService.WHISPER_PROVIDER))) {
            return attemptTranscribeOpenAi(audioFile, diarize, language, speakers);
        }
        return attemptTranscribeLocal(audioFile, diarize, language);
    }

    /**
     * OpenAI-kompatible Audio-API: multipart mit file/model/response_format.
     * verbose_json liefert Segmente mit Zeitstempeln; Modelle, die nur "json"
     * koennen (z.B. gpt-4o-transcribe), bekommen automatisch einen zweiten
     * Versuch ohne Zeitstempel.
     */
    private TranscriptionResult attemptTranscribeOpenAi(Path audioFile, boolean diarize, String language,
                                                        SpeakerContext speakers) {
        String url = settings.get(SettingsService.WHISPER_OPENAI_URL);
        String apiKey = settings.get(SettingsService.WHISPER_OPENAI_API_KEY);
        String model = settings.get(SettingsService.WHISPER_OPENAI_MODEL);
        String initialPrompt = settings.get(SettingsService.WHISPER_INITIAL_PROMPT);
        int timeoutSec = settings.getInt(SettingsService.WHISPER_TIMEOUT_SEC);

        if (apiKey.isBlank()) {
            return new TranscriptionResult(false, null,
                    "Kein API-Key fuer die Cloud-Spracherkennung hinterlegt (Einstellung whisper.openaiApiKey)");
        }
        if (diarize) {
            TranscriptionResult diarized = transcribeOpenAiDiarized(url, apiKey, audioFile, language,
                    speakers != null ? speakers : new SpeakerContext(), timeoutSec);
            // Kennt der Anbieter das Diarisierungs-Modell nicht (z.B. ein
            // kompatibler Dienst ohne Sprechererkennung), lieber ein Transkript
            // ohne Sprecher als eine endlos scheiternde Auswertung.
            if (diarized.success() || !isClientError(diarized.error())) return diarized;
            log.warn("Sprechererkennung in der Cloud abgelehnt ({}) - Transkript ohne Sprecher-Labels",
                    diarized.error());
        }

        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry("model", model));
        fields.add(Map.entry("response_format", "verbose_json"));
        if (!language.isBlank()) fields.add(Map.entry("language", language));
        if (!initialPrompt.isBlank()) fields.add(Map.entry("prompt", initialPrompt));

        TranscriptionResult result = render(postMultipart(url, apiKey, "file", audioFile, fields, timeoutSec));
        if (!result.success() && result.error() != null && result.error().contains("response_format")) {
            log.info("Modell {} unterstuetzt verbose_json nicht - zweiter Versuch mit response_format=json", model);
            fields.set(1, Map.entry("response_format", "json"));
            result = render(postMultipart(url, apiKey, "file", audioFile, fields, timeoutSec));
        }
        return result;
    }

    /**
     * Cloud-Diarisierung: diarized_json liefert Segmente mit Sprecher, Start und
     * Ende. Bekannte Sprecher aus frueheren Segmenten gehen als Stimmproben mit,
     * neue Stimmen bekommen fortlaufende Labels und - solange Platz ist - selbst
     * eine Stimmprobe fuer die folgenden Segmente.
     */
    private TranscriptionResult transcribeOpenAiDiarized(String url, String apiKey, Path audioFile,
                                                         String language, SpeakerContext speakers,
                                                         int timeoutSec) {
        String model = settings.get(SettingsService.WHISPER_OPENAI_DIARIZE_MODEL);
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry("model", model));
        fields.add(Map.entry("response_format", "diarized_json"));
        // Pflicht bei Audio ueber 30 Sekunden.
        fields.add(Map.entry("chunking_strategy", "auto"));
        if (!language.isBlank()) fields.add(Map.entry("language", language));
        // Bewusst kein "prompt": das Diarisierungs-Modell nimmt keinen an.
        for (Map.Entry<String, String> ref : speakers.references.entrySet()) {
            fields.add(Map.entry("known_speaker_names[]", ref.getKey()));
            fields.add(Map.entry("known_speaker_references[]", ref.getValue()));
        }
        log.info("Sprechererkennung per {} fuer {} ({} bekannte Sprecher)",
                model, audioFile.getFileName(), speakers.referenceCount());

        RawResponse raw = postMultipart(url, apiKey, "file", audioFile, fields, timeoutSec);
        if (!raw.success() && isClientError(raw.error()) && speakers.referenceCount() > 0) {
            // Eine unbrauchbare Stimmprobe soll nicht die ganze Sprechererkennung
            // kosten: einmal ohne Proben versuchen (Sprecher dann ggf. neu nummeriert).
            log.warn("Anfrage mit Stimmproben abgelehnt ({}) - erneut ohne Stimmproben", raw.error());
            fields.removeIf(f -> f.getKey().startsWith("known_speaker_"));
            raw = postMultipart(url, apiKey, "file", audioFile, fields, timeoutSec);
        }
        if (!raw.success()) return new TranscriptionResult(false, null, raw.error());
        try {
            JsonNode root = mapper.readTree(raw.body());
            JsonNode segments = root.get("segments");
            if (segments == null || !segments.isArray()) {
                return render(raw);
            }
            Map<String, String> labelMap = relabelSpeakers(segments, speakers);
            collectReferences(audioFile, segments, labelMap, speakers);
            return new TranscriptionResult(true, renderJsonTranscript(mapper.writeValueAsString(root)), null);
        } catch (IOException e) {
            return render(raw);
        }
    }

    /**
     * Ersetzt die Sprecher der Antwort durch stabile Labels: bekannte Namen
     * (aus den Stimmproben) bleiben, neue Stimmen (A, B, ...) bekommen das
     * naechste freie SPEAKER_xx.
     *
     * @return Zuordnung Antwort-Label -> stabiles Label
     */
    Map<String, String> relabelSpeakers(JsonNode segments, SpeakerContext speakers) {
        Map<String, String> labelMap = new HashMap<>();
        for (JsonNode seg : segments) {
            if (!seg.hasNonNull("speaker") || !(seg instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
                continue;
            }
            String original = seg.get("speaker").asText();
            String stable = labelMap.computeIfAbsent(original,
                    o -> speakers.references.containsKey(o) ? o : speakers.nextLabel());
            obj.put("speaker", stable);
        }
        return labelMap;
    }

    /**
     * Schneidet fuer neue Sprecher ohne Stimmprobe einen 2-10 s langen
     * Ausschnitt heraus (die laengste passende Aeusserung), solange die Cloud
     * noch weitere bekannte Sprecher annimmt.
     */
    private void collectReferences(Path audioFile, JsonNode segments, Map<String, String> labelMap,
                                   SpeakerContext speakers) {
        if (clipExtractor == null) return;
        for (String label : labelMap.values()) {
            if (speakers.references.containsKey(label)) continue;
            if (speakers.referenceCount() >= MAX_SPEAKER_REFERENCES) return;
            JsonNode best = null;
            double bestLength = 0;
            for (JsonNode seg : segments) {
                if (!label.equals(seg.path("speaker").asText(null))) continue;
                double length = seg.path("end").asDouble(0) - seg.path("start").asDouble(0);
                double usable = Math.min(length, MAX_REFERENCE_SECONDS);
                if (usable >= MIN_REFERENCE_SECONDS && usable > bestLength) {
                    best = seg;
                    bestLength = usable;
                }
            }
            if (best == null) continue;
            String dataUrl = referenceClip(audioFile, best.path("start").asDouble(0), bestLength);
            if (dataUrl != null) {
                speakers.references.put(label, dataUrl);
                log.info("Stimmprobe fuer {} aus {} uebernommen ({} s)", label, audioFile.getFileName(),
                        Math.round(bestLength));
            }
        }
    }

    private String referenceClip(Path audioFile, double start, double length) {
        Path wav = null;
        try {
            wav = Files.createTempFile("bbbbot-speaker-", ".wav");
            Path clip = clipExtractor.extract(audioFile, start, length, wav);
            if (clip == null) return null;
            return "data:audio/wav;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(clip));
        } catch (IOException e) {
            log.warn("Stimmprobe aus {} nicht lesbar: {}", audioFile.getFileName(), e.getMessage());
            return null;
        } finally {
            if (wav != null) {
                try { Files.deleteIfExists(wav); } catch (IOException ignored) {}
            }
        }
    }

    /** HTTP 4xx: die Anfrage selbst wurde abgelehnt (anders als Netz- oder Serverfehler). */
    private static boolean isClientError(String error) {
        return error != null && error.matches("(?s)^STT HTTP 4\\d\\d.*") && !error.startsWith("STT HTTP 401")
                && !error.startsWith("STT HTTP 429");
    }

    private TranscriptionResult attemptTranscribeLocal(Path audioFile, boolean diarize, String language) {
        String baseUrl = settings.get(SettingsService.WHISPER_URL);
        String output = settings.get(SettingsService.WHISPER_OUTPUT);
        boolean vad = settings.getBool(SettingsService.WHISPER_VAD_FILTER);
        String initialPrompt = settings.get(SettingsService.WHISPER_INITIAL_PROMPT);
        int timeoutSec = settings.getInt(SettingsService.WHISPER_TIMEOUT_SEC);

        StringBuilder query = new StringBuilder();
        appendParam(query, "task", "transcribe");
        // Ohne Sprachvorgabe (automatisch erkennen) den Parameter weglassen -
        // ein leeres language= ist keine gueltige Sprache.
        if (!language.isBlank()) {
            appendParam(query, "language", language);
        }
        appendParam(query, "output", output);
        appendParam(query, "vad_filter", String.valueOf(vad));
        if (diarize) {
            appendParam(query, "diarize", "true");
        }
        if (!initialPrompt.isBlank()) {
            appendParam(query, "initial_prompt", initialPrompt);
        }
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + query;
        return render(postMultipart(url, null, "audio_file", audioFile, List.of(), timeoutSec));
    }

    /** Rohantwort eines STT-Endpunkts (vor dem Umsetzen in ein Transkript). */
    private record RawResponse(boolean success, String body, String error) {}

    private TranscriptionResult render(RawResponse raw) {
        return raw.success()
                ? new TranscriptionResult(true, renderJsonTranscript(raw.body()), null)
                : new TranscriptionResult(false, null, raw.error());
    }

    /**
     * Multipart-Upload an einen STT-Endpunkt. Liefert die Rohantwort; Felder
     * duerfen sich wiederholen (known_speaker_names[] u.a.).
     */
    private RawResponse postMultipart(String url, String bearerToken, String fileField,
                                      Path audioFile, List<Map.Entry<String, String>> textFields, int timeoutSec) {
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
                return new RawResponse(false, null,
                        "STT HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));
            }
            return new RawResponse(true, response.body(), null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // ConnectException & Co. haben oft keine Message - Klassenname und URL helfen bei der Diagnose
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new RawResponse(false, null,
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
                                                           List<Map.Entry<String, String>> textFields)
            throws IOException {
        // Wichtig: als EIN byte[] mit bekannter Laenge senden. ofByteArrays() meldet
        // Laenge -1 -> HttpClient nutzt chunked Transfer-Encoding, was der Whisper-
        // Server nicht korrekt parst (audio_file fehlt -> HTTP 422). ofByteArray()
        // setzt dagegen Content-Length.
        var out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> field : textFields) {
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
