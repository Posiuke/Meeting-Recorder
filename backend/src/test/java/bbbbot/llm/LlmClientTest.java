package bbbbot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LlmClient.Answer read(String json) throws Exception {
        return LlmClient.readAnswer(MAPPER.readTree(json));
    }

    @Test
    void entferntThinkBloeckeVonReasoningModellen() {
        String content = "<think>\nInterne Ueberlegung...\n</think>\n\nDie Zusammenfassung.";
        assertEquals("Die Zusammenfassung.", LlmClient.stripReasoning(content));
    }

    @Test
    void laesstNormaleAntwortenUnveraendert() {
        assertEquals("Normale Antwort", LlmClient.stripReasoning("Normale Antwort"));
    }

    @Test
    void liestDieAntwortAusDemContent() throws Exception {
        LlmClient.Answer answer = read("""
                {"model":"qwen","choices":[{"index":0,"finish_reason":"stop",
                 "message":{"role":"assistant","content":"1 | Guten Morgen."}}]}
                """);

        assertThat(answer.content()).isEqualTo("1 | Guten Morgen.");
        assertThat(answer.error()).isNull();
    }

    /**
     * Der Fall aus dem Betrieb: Das Reasoning-Modell hat sein Token-Budget mit
     * Nachdenken verbraucht und content leer gelassen. Vorher stand im Log nur
     * "LLM-Antwort ohne Inhalt" samt 300 Zeichen JSON - die Ursache war daran
     * nicht zu erkennen.
     */
    @Test
    void benenntNachdenkenAlsUrsacheWennContentLeerBleibt() throws Exception {
        LlmClient.Answer answer = read("""
                {"id":"chatcmpl-8c17","object":"chat.completion","model":"qwen3-coder-next",
                 "choices":[{"index":0,"finish_reason":"length",
                  "message":{"role":"assistant","content":null,"refusal":null,
                   "reasoning":"Thinking Process:\\n\\n1. Der Nutzer moechte Saetze glaetten..."}}]}
                """);

        assertThat(answer.content()).isNull();
        assertThat(answer.error())
                .contains("nur intern nachgedacht")
                .contains("qwen3-coder-next")
                .contains("finish_reason=length")
                .contains("llm.disableThinking");
    }

    @Test
    void erkenntNachdenkenAuchImFeldReasoningContent() throws Exception {
        LlmClient.Answer answer = read("""
                {"model":"qwen3","choices":[{"index":0,"finish_reason":"length",
                 "message":{"role":"assistant","content":"",
                  "reasoning_content":"Ich ueberlege noch..."}}]}
                """);

        assertThat(answer.error()).contains("nur intern nachgedacht");
    }

    /** Bleibt vom content nach dem Entfernen des think-Blocks nichts uebrig, ist es derselbe Fall. */
    @Test
    void erkenntNachdenkenAuchAlsThinkBlockOhneAntwort() throws Exception {
        LlmClient.Answer answer = read("""
                {"model":"qwen3","choices":[{"index":0,"finish_reason":"length",
                 "message":{"role":"assistant","content":"<think>Ich ueberlege noch und noch</think>"}}]}
                """);

        assertThat(answer.content()).isNull();
        assertThat(answer.error()).contains("nur intern nachgedacht");
    }

    @Test
    void meldetLeereAntwortOhneReasoningMitStatus() throws Exception {
        LlmClient.Answer answer = read("""
                {"model":"mistral","choices":[{"index":0,"finish_reason":"stop",
                 "message":{"role":"assistant","content":""}}]}
                """);

        assertThat(answer.content()).isNull();
        assertThat(answer.error()).contains("ohne Inhalt").contains("finish_reason=stop");
    }

    /** Der Fall aus dem Betrieb: OpenAI lehnt den vLLM-Schalter ab. */
    @Test
    void erkenntAbgelehntenParameterAmFeldParam() {
        assertEquals("chat_template_kwargs", LlmClient.rejectedParam("""
                {"error":{"message":"Unknown parameter: 'chat_template_kwargs'.",
                 "type":"invalid_request_error","param":"chat_template_kwargs","code":"unknown_parameter"}}
                """));
    }

    @Test
    void erkenntAbgelehnteTemperaturUndAltesTokenFeld() {
        assertEquals("temperature", LlmClient.rejectedParam("""
                {"error":{"message":"Unsupported value: 'temperature' does not support 0.3 with this model.",
                 "param":"temperature","code":"unsupported_value"}}
                """));
        assertEquals("max_tokens", LlmClient.rejectedParam("""
                {"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.",
                 "param":null}}
                """));
    }

    @Test
    void ignoriertAndereFehler() {
        assertNull(LlmClient.rejectedParam("""
                {"error":{"message":"The model 'gpt-x' does not exist","param":"model","code":"model_not_found"}}
                """));
        assertNull(LlmClient.rejectedParam("Bad Request"));
    }

    @Test
    void cloudAnfrageOhneVllmSchalterUndMitNeuemTokenFeld() {
        ObjectNode body = LlmClient.buildBody("s", "u", "gpt-5", 0.3, 500, true, "low", false, Set.of());
        assertThat(body.has("chat_template_kwargs")).isFalse();
        assertThat(body.has("max_tokens")).isFalse();
        assertEquals(500, body.path("max_completion_tokens").asInt());
        assertEquals("low", body.path("reasoning_effort").asText());
    }

    @Test
    void cloudAnfrageLaesstAbgelehnteParameterWeg() {
        ObjectNode body = LlmClient.buildBody("s", "u", "gpt-4o", 0.3, 500, true, "low", false,
                Set.of("temperature", "reasoning_effort", "max_completion_tokens"));
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
        assertEquals(500, body.path("max_tokens").asInt());
    }

    @Test
    void lokaleAnfrageBleibtWieGehabt() {
        ObjectNode body = LlmClient.buildBody("s", "u", "qwen", 0.3, 500, false, "off", true, Set.of());
        assertEquals(500, body.path("max_tokens").asInt());
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }

    @Test
    void modelllisteZeigtNurChatModelleSortiert() throws Exception {
        List<String> models = LlmClient.chatModels(MAPPER.readTree("""
                {"data":[{"id":"gpt-4o"},{"id":"text-embedding-3-small"},{"id":"whisper-1"},
                 {"id":"dall-e-3"},{"id":"chatgpt-4o-latest"},{"id":"tts-1"}]}
                """));
        assertEquals(List.of("chatgpt-4o-latest", "gpt-4o"), models);
    }
}
