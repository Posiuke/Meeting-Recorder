package bbbbot.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gegen echtes Amazon Bedrock. Laeuft nur mit {@code BEDROCK_IT_MODEL} (und
 * AWS-Zugangsdaten in der Umgebung), z.B.:
 * <pre>
 * eval "$(aws configure export-credentials --profile wikavis --format env)"
 * BEDROCK_IT_MODEL=eu.anthropic.claude-haiku-4-5-20251001-v1:0 AWS_REGION=eu-central-1 \
 *   mvn test -Dtest=BedrockChatIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_IT_MODEL", matches = ".+")
class BedrockChatIT {

    private final BedrockChat bedrock = new BedrockChat();
    private final String model = System.getenv("BEDROCK_IT_MODEL");

    @Test
    void antwortetAufDeutsch() {
        BedrockChat.Reply reply = bedrock.converse("", model,
                "Du bist ein Verbindungstest. Antworte mit genau einem Wort.", "Antworte mit: OK", 0.2, 50, 60);
        if (reply.temperatureRejected()) {
            // Neuere Modelle (z.B. Claude Sonnet 5.5) kennen keine Temperatur mehr -
            // LlmClient wiederholt dann ohne; hier dasselbe von Hand.
            reply = bedrock.converse("", model,
                    "Du bist ein Verbindungstest. Antworte mit genau einem Wort.", "Antworte mit: OK", null, 50, 60);
        }
        assertThat(reply.error()).isNull();
        assertThat(reply.content()).containsIgnoringCase("OK");
    }

    @Test
    void deutscheZusammenfassung() {
        BedrockChat.Reply reply = bedrock.converse("", model,
                "Fasse das Transkript in einem deutschen Satz zusammen.",
                "[00:01] Anna: Wir verschieben den Release auf Freitag.\n[00:05] Tom: Okay, ich informiere den Kunden.",
                null, 200, 60);
        assertThat(reply.error()).isNull();
        assertThat(reply.content()).containsIgnoringCase("Freitag");
    }

    @Test
    void modellListeEnthaeltEuProfileZuerst() {
        LlmClient.ModelList list = bedrock.listModels("eu-central-1");
        assertThat(list.success()).as(list.error()).isTrue();
        assertThat(list.models()).isNotEmpty();
        assertThat(list.models().get(0)).startsWith("eu.");
        assertThat(list.models()).noneMatch(m -> m.contains("embed") || m.contains("rerank"));
    }

    @Test
    void unbekanntesModellLiefertVerstaendlichenFehler() {
        BedrockChat.Reply reply = bedrock.converse("", "eu.gibt-es-nicht-v9", "s", "u", 0.2, 50, 30);
        assertThat(reply.content()).isNull();
        assertThat(reply.error()).contains("eu.gibt-es-nicht-v9");
    }
}
