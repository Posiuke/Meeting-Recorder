package bbbbot.stt.transcribe;

import bbbbot.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Spracherkennung ueber ElevenLabs Scribe (POST /v1/speech-to-text).
 *
 * <p>Die Datei geht direkt als multipart an die API - ohne Zwischenablage, ohne
 * Auftrag, der hinterher aufzuraeumen waere. Die Antwort kommt synchron und
 * enthaelt jedes Wort mit Zeit und Sprecher; daraus werden wie bei Amazon
 * Transcribe Saetze je Sprecher gebaut.
 *
 * <p>Fachbegriffe kann ElevenLabs als {@code keyterms} beruecksichtigen; sie
 * kommen auf Wunsch aus dem gemeinsamen Glossar
 * ({@code whisper.elevenlabsKeyterms}, kostet bei ElevenLabs einen Aufschlag).
 */
@Component
public class ElevenLabsTranscriber implements WholeFileTranscriber {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsTranscriber.class);

    /** ElevenLabs nimmt Aufnahmen bis 15 Stunden an. */
    public static final long MAX_DURATION_MS = 15L * 60 * 60 * 1000 - 60_000;

    /**
     * Mindestwartezeit auf die Antwort: Die ganze Aufnahme geht in EINER
     * Anfrage hin, Hochladen und Erkennen einer mehrstuendigen Sitzung dauern
     * laenger als ein einzelnes Segment bei den anderen Anbietern.
     */
    static final int MIN_TIMEOUT_SEC = 1800;

    /** Grenzen der API fuer keyterms. */
    static final int MAX_KEYTERMS = 1000;
    static final int MAX_KEYTERM_LENGTH = 50;
    static final int MAX_KEYTERM_WORDS = 5;

    private final SettingsService settings;
    /** Begriffe des gemeinsamen Glossars; als Lieferant, damit Tests ohne Datenbank auskommen. */
    private final Supplier<Collection<String>> glossaryTerms;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    @org.springframework.beans.factory.annotation.Autowired
    public ElevenLabsTranscriber(SettingsService settings, bbbbot.llm.GlossaryService glossary) {
        this(settings, () -> glossary.entriesOf(null).stream().map(bbbbot.domain.GlossaryEntry::getTerm).toList());
    }

    ElevenLabsTranscriber(SettingsService settings, Supplier<Collection<String>> glossaryTerms) {
        this.settings = settings;
        this.glossaryTerms = glossaryTerms;
    }

    @Override
    public long maxDurationMs() {
        return MAX_DURATION_MS;
    }

    @Override
    public String displayName() {
        return "ElevenLabs";
    }

    @Override
    public Result transcribe(Path audio, String language, boolean diarize) {
        String apiKey = settings.get(SettingsService.WHISPER_ELEVENLABS_API_KEY).trim();
        if (apiKey.isEmpty()) {
            return Result.failed("Kein API-Key fuer ElevenLabs hinterlegt (Einstellung whisper.elevenlabsApiKey)");
        }
        List<String> keyterms = settings.getBool(SettingsService.WHISPER_ELEVENLABS_KEYTERMS)
                ? keyterms(glossaryTerms.get()) : List.of();
        int attempts = Math.max(1, settings.getInt(SettingsService.WHISPER_RETRY_ATTEMPTS));
        long baseMs = settings.getLong(SettingsService.WHISPER_RETRY_BASE_MS);

        Attempt attempt = null;
        for (int i = 1; i <= attempts; i++) {
            attempt = send(audio, apiKey, language, diarize, keyterms);
            if (attempt.result().success()) break;
            // Lehnt die API nur die Begriffe ab, lieber ohne sie als gar nicht.
            if (attempt.status() >= 400 && attempt.status() < 500 && !keyterms.isEmpty()
                    && String.valueOf(attempt.result().error()).toLowerCase(Locale.ROOT).contains("keyterm")) {
                log.warn("ElevenLabs lehnt die Glossarbegriffe ab ({}) - erneut ohne", attempt.result().error());
                keyterms = List.of();
                attempt = send(audio, apiKey, language, diarize, keyterms);
                if (attempt.result().success()) break;
            }
            if (!retryable(attempt.status()) || i == attempts) break;
            long wait = baseMs * (1L << (i - 1));
            log.warn("ElevenLabs-Versuch {}/{} fuer {} fehlgeschlagen ({}) - erneut in {} ms",
                    i, attempts, audio.getFileName(), attempt.result().error(), wait);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.failed("Unterbrochen");
            }
        }
        return attempt.result();
    }

    /** Ergebnis einer Anfrage samt HTTP-Status (0 = keine Antwort). */
    private record Attempt(Result result, int status) {}

    /** Netzfehler, Ueberlast (429) und Serverfehler lohnen einen zweiten Versuch, alles andere nicht. */
    private static boolean retryable(int status) {
        return status == 0 || status == 429 || status >= 500;
    }

    private Attempt send(Path audio, String apiKey, String language, boolean diarize, List<String> keyterms) {
        String url = settings.get(SettingsService.WHISPER_ELEVENLABS_URL).trim();
        if (settings.getBool(SettingsService.WHISPER_ELEVENLABS_ZERO_RETENTION)) {
            url += (url.contains("?") ? "&" : "?") + "enable_logging=false";
        }
        List<Map.Entry<String, String>> fields = fields(language, diarize, keyterms);
        int timeoutSec = Math.max(MIN_TIMEOUT_SEC, settings.getInt(SettingsService.WHISPER_TIMEOUT_SEC));
        try {
            String boundary = "----meetingrecorder" + UUID.randomUUID();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("xi-api-key", apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(multipartBody(boundary, fields, audio))
                    .build();
            log.info("ElevenLabs ({}): {} - Sprache {}, Sprecher {}, {} Glossarbegriff(e)",
                    settings.get(SettingsService.WHISPER_ELEVENLABS_MODEL), audio.getFileName(),
                    language == null || language.isBlank() ? "automatisch" : language, diarize, keyterms.size());
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Attempt(Result.failed("ElevenLabs HTTP " + response.statusCode() + ": "
                        + truncate(response.body(), 500)), response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            return new Attempt(new Result(true, parse(root), root.path("language_code").asText(null), null), 200);
        } catch (IOException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Attempt(Result.failed("ElevenLabs nicht erreichbar (" + url + "): " + reason), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt(Result.failed("Unterbrochen"), -1);
        }
    }

    /** Formularfelder der Anfrage; Felder duerfen sich wiederholen (keyterms). */
    List<Map.Entry<String, String>> fields(String language, boolean diarize, List<String> keyterms) {
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry("model_id", settings.get(SettingsService.WHISPER_ELEVENLABS_MODEL).trim()));
        fields.add(Map.entry("timestamps_granularity", "word"));
        // Keine "(Lachen)"/"(Applaus)"-Einschuebe im Protokoll.
        fields.add(Map.entry("tag_audio_events", "false"));
        fields.add(Map.entry("diarize", Boolean.toString(diarize)));
        int maxSpeakers = settings.getInt(SettingsService.WHISPER_ELEVENLABS_MAX_SPEAKERS);
        if (diarize && maxSpeakers > 0) fields.add(Map.entry("num_speakers", Integer.toString(maxSpeakers)));
        String code = languageCode(language);
        if (code != null) fields.add(Map.entry("language_code", code));
        for (String term : keyterms) fields.add(Map.entry("keyterms", term));
        return fields;
    }

    /** ISO-Sprache der Aufnahme ("de", "pt-br") als ISO-639-1 fuer die API; null = automatisch erkennen. */
    static String languageCode(String language) {
        if (language == null || language.isBlank()) return null;
        String base = language.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return base.isEmpty() ? null : base;
    }

    /**
     * Glossarbegriffe, die ElevenLabs annimmt: hoechstens 50 Zeichen und 5
     * Woerter, ohne spitze, geschweifte und eckige Klammern oder Backslash;
     * doppelte fallen weg, mehr als 1000 werden abgeschnitten.
     */
    static List<String> keyterms(Collection<String> terms) {
        Set<String> out = new LinkedHashSet<>();
        for (String term : terms) {
            if (term == null) continue;
            String t = term.trim().replaceAll("\\s+", " ");
            if (t.isEmpty() || t.length() > MAX_KEYTERM_LENGTH) continue;
            if (t.split(" ").length > MAX_KEYTERM_WORDS) continue;
            if (t.matches(".*[<>{}\\[\\]\\\\].*")) continue;
            out.add(t);
            if (out.size() >= MAX_KEYTERMS) break;
        }
        return new ArrayList<>(out);
    }

    /**
     * Woerter der Antwort ({@code words}) zu Saetzen je Sprecher - mit
     * denselben Regeln wie bei Amazon Transcribe: Eine Zeile endet am
     * Satzzeichen, beim Sprecherwechsel, nach einer langen Pause oder nach 30 s.
     * Ohne Wortliste bleibt der Gesamttext als eine Zeile.
     */
    static List<TranscribeResult.Utterance> parse(JsonNode root) {
        List<TranscribeResult.Utterance> out = new ArrayList<>();
        JsonNode words = root.path("words");
        if (!words.isArray() || words.isEmpty()) {
            String text = root.path("text").asText("").trim();
            if (!text.isEmpty()) out.add(new TranscribeResult.Utterance(0, 0, null, text));
            return out;
        }
        StringBuilder text = null;
        double start = 0;
        double end = 0;
        String speaker = null;
        boolean space = false;
        for (JsonNode word : words) {
            String type = word.path("type").asText("word");
            if ("spacing".equals(type)) {
                space = true;
                continue;
            }
            if (!"word".equals(type)) continue; // audio_event
            String content = word.path("text").asText("").trim();
            if (content.isEmpty()) continue;
            double wordStart = word.path("start").asDouble(end);
            double wordEnd = word.path("end").asDouble(wordStart);
            String wordSpeaker = word.hasNonNull("speaker_id")
                    ? TranscribeResult.label(word.get("speaker_id").asText()) : null;
            boolean newLine = text == null
                    || !Objects.equals(wordSpeaker, speaker)
                    || wordStart - end > TranscribeResult.PAUSE_SECONDS
                    || wordStart - start > TranscribeResult.MAX_UTTERANCE_SECONDS;
            if (newLine) {
                if (text != null) out.add(new TranscribeResult.Utterance(start, end, speaker, text.toString()));
                text = new StringBuilder(content);
                start = wordStart;
                speaker = wordSpeaker;
            } else {
                if (space) text.append(' ');
                text.append(content);
            }
            end = wordEnd;
            space = false;
            if (content.endsWith(".") || content.endsWith("?") || content.endsWith("!")) {
                out.add(new TranscribeResult.Utterance(start, end, speaker, text.toString()));
                text = null;
            }
        }
        if (text != null) out.add(new TranscribeResult.Utterance(start, end, speaker, text.toString()));
        return out;
    }

    /**
     * Multipart-Koerper mit bekannter Laenge; die Audiodatei wird gestreamt
     * statt in den Speicher geladen - eine ganze Aufnahme kann einige hundert
     * Megabyte gross sein.
     */
    private static HttpRequest.BodyPublisher multipartBody(String boundary, List<Map.Entry<String, String>> fields,
                                                           Path audio) throws IOException {
        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> field : fields) {
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(field.getKey()).append("\"\r\n\r\n")
                    .append(field.getValue()).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(audio.getFileName()).append("\"\r\n")
                .append("Content-Type: audio/mpeg\r\n\r\n");
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(head.toString().getBytes(StandardCharsets.UTF_8)),
                HttpRequest.BodyPublishers.ofFile(audio),
                HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
