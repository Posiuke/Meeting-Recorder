package bbbbot.llm;

import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmClientBedrockTest {

    private final SettingsService settings = mock(SettingsService.class);
    private final BedrockChat bedrock = mock(BedrockChat.class);
    private LlmClient client;

    @BeforeEach
    void setUp() {
        when(settings.isLlmBedrock()).thenReturn(true);
        when(settings.llmModel()).thenReturn("eu.anthropic.claude-sonnet-5-5");
        when(settings.get(SettingsService.LLM_BEDROCK_REGION)).thenReturn("eu-central-1");
        when(settings.getDouble(SettingsService.LLM_TEMPERATURE)).thenReturn(0.3);
        when(settings.getInt(SettingsService.LLM_MAX_TOKENS)).thenReturn(2048);
        when(settings.getInt(SettingsService.LLM_TIMEOUT_SEC)).thenReturn(60);
        when(settings.getInt(SettingsService.LLM_RETRY_ATTEMPTS)).thenReturn(2);
        when(settings.getLong(SettingsService.LLM_RETRY_BASE_MS)).thenReturn(0L);
        client = new LlmClient(settings, bedrock);
    }

    @Test
    void abgelehnteTemperaturWirdWeggelassenUndGemerkt() {
        when(bedrock.converse(anyString(), anyString(), anyString(), anyString(), eq(0.3), anyInt(), anyInt()))
                .thenReturn(new BedrockChat.Reply(null, "`temperature` is deprecated", true));
        when(bedrock.converse(anyString(), anyString(), anyString(), anyString(), isNull(), anyInt(), anyInt()))
                .thenReturn(new BedrockChat.Reply("Antwort", null, false));

        assertThat(client.chat("s", "u").content()).isEqualTo("Antwort");
        assertThat(client.chat("s", "u").content()).isEqualTo("Antwort");
        // Beim zweiten Mal gleich ohne Temperatur
        verify(bedrock, times(1)).converse(anyString(), anyString(), anyString(), anyString(), eq(0.3), anyInt(), anyInt());
    }

    @Test
    void modellDerVorlageGehtVorUndFehlerWerdenWiederholt() {
        when(bedrock.converse(anyString(), eq("eu.amazon.nova-pro-v1:0"), anyString(), anyString(), any(), anyInt(),
                anyInt()))
                .thenReturn(new BedrockChat.Reply(null, "Bedrock nicht erreichbar", false))
                .thenReturn(new BedrockChat.Reply("ok", null, false));

        LlmClient.LlmResult result = client.chat("s", "u",
                LlmClient.Overrides.modelAndTemperature("eu.amazon.nova-pro-v1:0", 0.1));
        assertThat(result.success()).isTrue();
    }

    @Test
    void endgueltigerFehlerKommtDurch() {
        when(bedrock.converse(anyString(), anyString(), anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new BedrockChat.Reply(null, "Model access is denied", false));
        LlmClient.LlmResult result = client.chat("s", "u");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("access is denied");
    }

    @Test
    void embeddingsUndRerankerSindKeineChatModelle() {
        assertThat(BedrockChat.isChatModel("eu.anthropic.claude-sonnet-5-5")).isTrue();
        assertThat(BedrockChat.isChatModel("eu.cohere.embed-v4:0")).isFalse();
        assertThat(BedrockChat.isChatModel("amazon.rerank-v1:0")).isFalse();
    }
}
