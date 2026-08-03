package bbbbot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
