package bbbbot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client fuer das LLM: lokal ein OpenAI-kompatibler Server (vLLM mit Qwen) oder
 * die oeffentliche OpenAI-API (Einstellung llm.provider).
 * Ruft /chat/completions mit Retry und exponentiellem Backoff auf;
 * Modell, Temperatur, Token-Limit und Timeouts kommen aus den Einstellungen.
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final SettingsService settings;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ObjectMapper mapper = JSON;

    /**
     * EIN Client fuer alle Aufrufe. Vorher wurde je Anfrage ein neuer gebaut -
     * das kostet jedes Mal eine neue TCP-Verbindung (kein Keep-Alive) und laesst
     * pro Client zwei Threads zurueck, die erst der Garbage Collector aufraeumt.
     * Bei einer Zusammenfassung (ein Aufruf je Aufnahme) fiel das nicht auf; seit
     * die Transkript-Glaettung pro Aufnahme dutzende Aufrufe macht, sammeln sich
     * Threads und Verbindungen so lange an, bis auch gesunde Anfragen in den
     * Timeout laufen.
     */
    private volatile HttpClient httpClient;

    public LlmClient(SettingsService settings) {
        this.settings = settings;
    }

    private HttpClient client() {
        HttpClient existing = httpClient;
        if (existing != null) return existing;
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                        // HTTP/1.1 erzwingen: der Default-Client versucht ein HTTP/2-
                        // (h2c-)Upgrade; bei "Connection: Upgrade" verwirft der vLLM-
                        // Server (uvicorn) den Request-Body -> HTTP 400 "body: None".
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build();
            }
            return httpClient;
        }
    }

    public record LlmResult(boolean success, String content, String error) {}

    /**
     * Abweichungen von den Admin-Vorgaben fuer EINE Anfrage. Jedes Feld
     * {@code null} heisst "Vorgabe verwenden" - so bleibt am Aufrufer sichtbar,
     * was er bewusst festlegt.
     *
     * @param model       Modell dieser Anfrage; {@code null} = {@code llm.model}.
     *                    Auswertungen duerfen ein anderes Modell nutzen als die
     *                    Glaettung, damit zwei Modelle an derselben Aufnahme
     *                    vergleichbar sind.
     * @param temperature Temperatur dieser Anfrage; {@code null} = {@code llm.temperature}
     * @param maxTokens   Token-Budget dieser Anfrage; {@code null} = Standard des
     *                    Admins ({@code llm.maxTokens}). Die Transkript-Glaettung
     *                    rechnet sich ihr Budget selbst aus, weil ihre Antwort etwa
     *                    so lang ist wie die Eingabe: Der Admin-Standard wuerde sie
     *                    abschneiden, wenn er kleiner ist - und das Modell zu
     *                    unnoetig langer Ausgabe verleiten, wenn er groesser ist.
     *                    Der Wert gilt daher genau so, wie er hier ankommt.
     */
    public record Overrides(String model, Double temperature, Integer maxTokens) {

        /** Nichts abweichend - alles nach Admin-Vorgabe. */
        public static Overrides none() {
            return new Overrides(null, null, null);
        }

        /** Nur ein eigenes Token-Budget (Transkript-Glaettung). */
        public static Overrides maxTokens(Integer maxTokens) {
            return new Overrides(null, null, maxTokens);
        }

        /** Modell und Temperatur einer Auswertung. */
        public static Overrides modelAndTemperature(String model, Double temperature) {
            return new Overrides(model, temperature, null);
        }
    }

    public LlmResult chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, Overrides.none());
    }

    public LlmResult chat(String systemPrompt, String userPrompt, Overrides overrides) {
        boolean cloud = settings.isLlmCloud();
        String baseUrl = settings.get(cloud ? SettingsService.LLM_OPENAI_URL : SettingsService.LLM_BASE_URL);
        String model = overrides.model() == null || overrides.model().isBlank()
                ? settings.llmModel()
                : overrides.model().trim();
        String apiKey = settings.get(cloud ? SettingsService.LLM_OPENAI_API_KEY : SettingsService.LLM_API_KEY);
        double temperature = overrides.temperature() == null
                ? settings.getDouble(SettingsService.LLM_TEMPERATURE)
                : overrides.temperature();
        int maxTokens = overrides.maxTokens() == null
                ? settings.getInt(SettingsService.LLM_MAX_TOKENS)
                : overrides.maxTokens();
        int timeoutSec = settings.getInt(SettingsService.LLM_TIMEOUT_SEC);
        int retryAttempts = Math.max(1, settings.getInt(SettingsService.LLM_RETRY_ATTEMPTS));
        long retryBaseMs = settings.getLong(SettingsService.LLM_RETRY_BASE_MS);
        String reasoningEffort = cloud
                ? settings.get(SettingsService.LLM_OPENAI_REASONING_EFFORT).trim().toLowerCase(Locale.ROOT)
                : "off";
        boolean disableThinking = !cloud && settings.getBool(SettingsService.LLM_DISABLE_THINKING);

        if (cloud && apiKey.isBlank()) {
            return new LlmResult(false, null,
                    "Kein API-Key fuer das Cloud-LLM hinterlegt (Einstellung llm.openaiApiKey)");
        }

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        Set<String> rejected = rejectedParams.computeIfAbsent(url + "|" + model,
                k -> ConcurrentHashMap.newKeySet());
        String lastError = null;

        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            long begin = System.nanoTime();
            try {
                HttpResponse<String> response;
                // Lehnt das Modell einen unserer optionalen Parameter ab, wird sofort
                // ohne ihn wiederholt - das zaehlt nicht als Fehlversuch. Die Schleife
                // endet sicher, weil jeder Parameter nur einmal wegfallen kann.
                while (true) {
                    ObjectNode body = buildBody(systemPrompt, userPrompt, model, temperature, maxTokens,
                            cloud, reasoningEffort, disableThinking, rejected);
                    HttpRequest.Builder request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(timeoutSec))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
                    if (!apiKey.isBlank()) {
                        request.header("Authorization", "Bearer " + apiKey);
                    }
                    response = client().send(request.build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 400) break;
                    String param = rejectedParam(response.body());
                    if (param == null || !rejected.add(param)) break;
                    log.info("Modell {} lehnt Parameter {} ab - neuer Versuch ohne ihn", model, param);
                }
                if (response.statusCode() != 200) {
                    lastError = "LLM HTTP " + response.statusCode() + ": " + truncate(response.body(), 500);
                } else {
                    Answer answer = readAnswer(mapper.readTree(response.body()));
                    if (answer.content() != null) {
                        return new LlmResult(true, answer.content(), null);
                    }
                    lastError = answer.error();
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return new LlmResult(false, null, "Unterbrochen");
                }
                // ConnectException & Co. haben oft keine Message - Klassenname und URL helfen bei der Diagnose.
                // Die Dauer steht mit dabei: Ein Timeout nach 300 s ist ein anderes
                // Problem als ein "connection refused" nach 3 ms.
                lastError = "LLM-Endpunkt nicht erreichbar (" + url + ") nach "
                        + (System.nanoTime() - begin) / 1_000_000_000 + " s: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            log.warn("LLM-Versuch {}/{} fehlgeschlagen (Modell {}, max_tokens={}, Timeout {} s): {}",
                    attempt, retryAttempts, model, maxTokens, timeoutSec, lastError);
            if (attempt < retryAttempts) {
                try {
                    Thread.sleep(retryBaseMs * (1L << (attempt - 1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new LlmResult(false, null, "Unterbrochen");
                }
            }
        }
        return new LlmResult(false, null, lastError);
    }

    /**
     * Parameter, die ein Modell ablehnen darf, ohne dass die Anfrage scheitert.
     * Hintergrund: OpenAI weist unbekannte oder nicht unterstuetzte Parameter mit
     * HTTP 400 zurueck, statt sie zu ignorieren - und welche ein Modell kennt,
     * haengt vom Modell ab (Reasoning-Modelle: keine Temperatur, aeltere Modelle:
     * kein reasoning_effort, manche kompatible Anbieter: kein max_completion_tokens).
     */
    static final Set<String> ADAPTABLE_PARAMS = Set.of(
            "temperature", "reasoning_effort", "max_completion_tokens", "max_tokens", "chat_template_kwargs");

    /** Je Endpunkt und Modell die Parameter, die dort schon abgelehnt wurden. */
    private final Map<String, Set<String>> rejectedParams = new ConcurrentHashMap<>();

    static ObjectNode buildBody(String systemPrompt, String userPrompt, String model, double temperature,
                                int maxTokens, boolean cloud, String reasoningEffort,
                                boolean disableThinking, Set<String> rejected) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        if (!rejected.contains("temperature")) {
            body.put("temperature", temperature);
        }
        // OpenAI hat max_tokens durch max_completion_tokens ersetzt; Reasoning-Modelle
        // kennen nur noch das neue Feld. Lokale Server (vLLM) bleiben beim alten.
        boolean newTokenField = cloud ? !rejected.contains("max_completion_tokens") : rejected.contains("max_tokens");
        body.put(newTokenField ? "max_completion_tokens" : "max_tokens", maxTokens);
        if (cloud && !"off".equals(reasoningEffort) && !reasoningEffort.isBlank()
                && !rejected.contains("reasoning_effort")) {
            body.put("reasoning_effort", reasoningEffort);
        }
        if (disableThinking && !rejected.contains("chat_template_kwargs")) {
            // Reasoning-Modelle (Qwen3 & Co.) denken im SELBEN Token-Budget, aus dem
            // auch die Antwort kommt. Beim Glaetten reicht das nicht: Das Modell
            // verbraucht das Budget mit Nachdenken und liefert content = null. Der
            // Schalter ist der dokumentierte Weg bei vLLM und llama.cpp.
            body.putObject("chat_template_kwargs").put("enable_thinking", false);
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return body;
    }

    /**
     * Welcher unserer optionalen Parameter in einer HTTP-400-Antwort bemaengelt
     * wird - {@code null}, wenn es um etwas anderes geht. Liest {@code error.param}
     * (OpenAI) und sucht ersatzweise den Namen in der Fehlermeldung.
     */
    static String rejectedParam(String responseBody) {
        if (responseBody == null) return null;
        try {
            JsonNode error = JSON.readTree(responseBody).path("error");
            String param = error.path("param").asText("");
            if (ADAPTABLE_PARAMS.contains(param)) return param;
            String message = error.path("message").asText("");
            // max_tokens vor max_completion_tokens pruefen: Die Meldung zum alten Feld
            // nennt das neue als Ersatz ("Use 'max_completion_tokens' instead").
            for (String candidate : List.of("chat_template_kwargs", "reasoning_effort", "temperature",
                    "max_tokens", "max_completion_tokens")) {
                if (message.contains("'" + candidate + "'") || message.contains("\"" + candidate + "\"")) {
                    return candidate;
                }
            }
        } catch (IOException ignored) {
            // kein JSON - dann ist es kein Parameterfehler, den wir beheben koennen
        }
        return null;
    }

    public record ModelList(boolean success, List<String> models, String error) {}

    /**
     * Fragt die verfuegbaren Modelle beim Cloud-Anbieter ab ({@code GET /models}).
     * Adresse und Key kommen vom Aufrufer, damit die Admin-Oberflaeche sie schon
     * vor dem Speichern ausprobieren kann; leer = gespeicherte Cloud-Einstellung.
     */
    public ModelList listModels(String baseUrl, String apiKey) {
        String base = baseUrl == null || baseUrl.isBlank() ? settings.get(SettingsService.LLM_OPENAI_URL) : baseUrl.trim();
        String key = apiKey == null || apiKey.isBlank() ? settings.get(SettingsService.LLM_OPENAI_API_KEY) : apiKey.trim();
        String url = base.replaceAll("/+$", "") + "/models";
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            if (!key.isBlank()) {
                request.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> response = client().send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new ModelList(false, List.of(),
                        "Modellliste HTTP " + response.statusCode() + ": " + truncate(response.body(), 300));
            }
            return new ModelList(true, chatModels(mapper.readTree(response.body())), null);
        } catch (IllegalArgumentException e) {
            return new ModelList(false, List.of(), "Ungueltige Adresse: " + url);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ModelList(false, List.of(), "Anbieter nicht erreichbar (" + url + "): "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Modell-IDs aus {@code {"data":[{"id":...}]}}, ohne die, die sicher keine
     * Chat-Modelle sind (Embeddings, Sprache, Bilder, Moderation). Bleibt danach
     * nichts uebrig, kommt die volle Liste - lieber zu viel als eine leere Auswahl.
     */
    static List<String> chatModels(JsonNode root) {
        List<String> all = new ArrayList<>();
        for (JsonNode entry : root.path("data")) {
            String id = entry.path("id").asText("");
            if (!id.isBlank()) all.add(id);
        }
        List<String> chat = all.stream()
                .filter(id -> NON_CHAT_MARKERS.stream().noneMatch(id.toLowerCase(Locale.ROOT)::contains))
                .toList();
        List<String> result = new ArrayList<>(chat.isEmpty() ? all : chat);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private static final List<String> NON_CHAT_MARKERS = List.of(
            "embedding", "whisper", "tts", "transcribe", "dall-e", "image", "moderation",
            "realtime", "audio", "babbage", "davinci", "sora");

    /**
     * Inhalt einer Modellantwort - oder die Begruendung, warum nichts Verwertbares
     * dabei war. Genau eines der beiden Felder ist gesetzt.
     */
    record Answer(String content, String error) {
        static Answer of(String content) {
            return new Answer(content, null);
        }

        static Answer none(String error) {
            return new Answer(null, error);
        }
    }

    /**
     * Liest die Antwort aus {@code choices[0].message}.
     *
     * <p>Der wichtige Fall steht in der Mitte: Reasoning-Modelle liefern ihr
     * Nachdenken in {@code reasoning} bzw. {@code reasoning_content} und lassen
     * {@code content} leer, wenn das Token-Budget vom Nachdenken aufgebraucht wurde
     * ({@code finish_reason: "length"}). Fuer den Aufrufer ist das nicht von einem
     * kaputten Server zu unterscheiden - deshalb wird es hier ausdruecklich benannt,
     * samt der Einstellung, die hilft.
     */
    static Answer readAnswer(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("unbekannt");
        String model = root.path("model").asText("");

        JsonNode content = message.path("content");
        String text = content.isTextual() ? stripReasoning(content.asText()) : "";
        if (!text.isBlank()) {
            return Answer.of(text);
        }

        // Nachdenken kann in einem eigenen Feld stehen (reasoning/reasoning_content)
        // oder als <think>-Block im content, von dem dann nichts uebrig bleibt.
        String reasoning = firstText(message, "reasoning_content", "reasoning");
        int reasoningChars = reasoning != null ? reasoning.length()
                : content.isTextual() ? content.asText().length() : 0;
        if (reasoningChars > 0) {
            return Answer.none("Das Modell hat nur intern nachgedacht und keine Antwort geschrieben"
                    + " (Modell " + model + ", finish_reason=" + finishReason + ", "
                    + reasoningChars + " Zeichen Reasoning, Antwort leer)."
                    + " Abhilfe: Einstellung llm.disableThinking auf true setzen (oder das"
                    + " Nachdenken am LLM-Server abschalten); ersatzweise llm.maxTokens erhoehen.");
        }
        // OpenAI gibt das Nachdenken nicht heraus, sondern zaehlt es nur mit: Ist
        // das Budget erschoepft, bleibt content leer und finish_reason ist "length".
        String hint = "length".equals(finishReason)
                ? " Das Token-Budget war erschoepft - llm.maxTokens erhoehen oder bei"
                        + " Cloud-Modellen llm.openaiReasoningEffort senken."
                : "";
        return Answer.none("LLM-Antwort ohne Inhalt (Modell " + model + ", finish_reason="
                + finishReason + ")." + hint + " " + truncate(root.toString(), 200));
    }

    /** Erster der genannten Felder, der Text enthaelt - Server benennen das unterschiedlich. */
    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    /** Entfernt <think>-Bloecke von Reasoning-Modellen (Qwen3) aus der Antwort. */
    static String stripReasoning(String content) {
        return content.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
