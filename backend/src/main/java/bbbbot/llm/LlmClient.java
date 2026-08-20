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

/**
 * Client fuer den OpenAI-kompatiblen LLM-Server (vLLM mit Qwen).
 * Ruft /chat/completions mit Retry und exponentiellem Backoff auf;
 * Modell, Temperatur, Token-Limit und Timeouts kommen aus den Einstellungen.
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();

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
        String baseUrl = settings.get(SettingsService.LLM_BASE_URL);
        String model = overrides.model() == null || overrides.model().isBlank()
                ? settings.get(SettingsService.LLM_MODEL)
                : overrides.model().trim();
        String apiKey = settings.get(SettingsService.LLM_API_KEY);
        double temperature = overrides.temperature() == null
                ? settings.getDouble(SettingsService.LLM_TEMPERATURE)
                : overrides.temperature();
        int maxTokens = overrides.maxTokens() == null
                ? settings.getInt(SettingsService.LLM_MAX_TOKENS)
                : overrides.maxTokens();
        int timeoutSec = settings.getInt(SettingsService.LLM_TIMEOUT_SEC);
        int retryAttempts = Math.max(1, settings.getInt(SettingsService.LLM_RETRY_ATTEMPTS));
        long retryBaseMs = settings.getLong(SettingsService.LLM_RETRY_BASE_MS);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (settings.getBool(SettingsService.LLM_DISABLE_THINKING)) {
            // Reasoning-Modelle (Qwen3 & Co.) denken im SELBEN Token-Budget, aus dem
            // auch die Antwort kommt. Beim Glaetten reicht das nicht: Das Modell
            // verbraucht das Budget mit Nachdenken und liefert content = null. Der
            // Schalter ist der dokumentierte Weg bei vLLM und llama.cpp; Server, die
            // ihn nicht kennen, ignorieren ihn.
            body.putObject("chat_template_kwargs").put("enable_thinking", false);
        }
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String lastError = null;

        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            long begin = System.nanoTime();
            try {
                HttpClient client = client();
                HttpRequest.Builder request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSec))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
                if (!apiKey.isBlank()) {
                    request.header("Authorization", "Bearer " + apiKey);
                }
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
        return Answer.none("LLM-Antwort ohne Inhalt (Modell " + model + ", finish_reason="
                + finishReason + "): " + truncate(root.toString(), 200));
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
