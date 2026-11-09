package bbbbot.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.FoundationModelSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceType;
import software.amazon.awssdk.services.bedrock.model.ModelModality;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * LLM ueber Amazon Bedrock (Converse-API). Anmeldung ueber die Standardkette
 * des AWS-SDK - auf ECS die Task-Rolle, es gibt also keinen Schluessel zu
 * pflegen. Modell ist eine Modell-ID oder ein Inference-Profil (z.B.
 * {@code eu.anthropic.claude-sonnet-5-5}, Anfragen bleiben dann in EU-Regionen).
 */
@Component
public class BedrockChat {

    private static final Logger log = LoggerFactory.getLogger(BedrockChat.class);

    /** Ein Client je Region und Timeout - beides kann der Admin zur Laufzeit aendern. */
    private final Map<String, BedrockRuntimeClient> runtimeClients = new ConcurrentHashMap<>();

    /** Ergebnis eines Aufrufs; genau eins von content/error ist gesetzt. */
    public record Reply(String content, String error, boolean temperatureRejected) {}

    public Reply converse(String region, String model, String systemPrompt, String userPrompt,
                          Double temperature, int maxTokens, int timeoutSec) {
        InferenceConfiguration.Builder config = InferenceConfiguration.builder().maxTokens(maxTokens);
        if (temperature != null) config.temperature(temperature.floatValue());
        try {
            ConverseResponse response = runtime(region, timeoutSec).converse(b -> b
                    .modelId(model)
                    .system(SystemContentBlock.fromText(systemPrompt))
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText(userPrompt)).build())
                    .inferenceConfig(config.build()));
            String text = response.output().message().content().stream()
                    .map(ContentBlock::text)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining())
                    .strip();
            text = LlmClient.stripReasoning(text);
            if (!text.isBlank()) return new Reply(text, null, false);
            String hint = response.stopReason() == StopReason.MAX_TOKENS
                    ? " Das Token-Budget war erschoepft - llm.maxTokens erhoehen." : "";
            return new Reply(null, "Bedrock-Antwort ohne Text (Modell " + model + ", stopReason="
                    + response.stopReasonAsString() + ")." + hint, false);
        } catch (ValidationException e) {
            String message = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage();
            boolean temperatureProblem = temperature != null && message != null
                    && message.toLowerCase(Locale.ROOT).contains("temperature");
            return new Reply(null, "Bedrock lehnt die Anfrage ab (Modell " + model + "): " + message,
                    temperatureProblem);
        } catch (SdkClientException e) {
            return new Reply(null, "Bedrock nicht erreichbar (Region " + regionName(region) + "): "
                    + e.getMessage(), false);
        } catch (SdkException e) {
            return new Reply(null, "Bedrock-Fehler (Modell " + model + "): " + e.getMessage(), false);
        }
    }

    /**
     * Fuer die Auswahl im Admin-Bereich: Inference-Profile (zuerst die der
     * eigenen Geografie, z.B. eu.*) und On-Demand-Modelle mit Textausgabe -
     * ohne Embeddings und Reranker.
     */
    public LlmClient.ModelList listModels(String region) {
        Region r = resolveRegion(region);
        try (BedrockClient client = BedrockClient.builder().region(r)
                .credentialsProvider(DefaultCredentialsProvider.builder().build()).build()) {
            List<String> profiles = client.listInferenceProfilesPaginator(b -> b.typeEquals("SYSTEM_DEFINED"))
                    .inferenceProfileSummaries().stream()
                    .map(InferenceProfileSummary::inferenceProfileId)
                    .filter(BedrockChat::isChatModel)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            List<String> models = client.listFoundationModels(b -> b
                            .byOutputModality(ModelModality.TEXT)
                            .byInferenceType(InferenceType.ON_DEMAND))
                    .modelSummaries().stream()
                    .map(FoundationModelSummary::modelId)
                    .filter(BedrockChat::isChatModel)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            String geo = r.id().split("-")[0] + ".";
            List<String> result = new ArrayList<>();
            profiles.stream().filter(p -> p.startsWith(geo)).forEach(result::add);
            profiles.stream().filter(p -> !p.startsWith(geo)).forEach(result::add);
            models.stream().filter(m -> !result.contains(m)).forEach(result::add);
            return new LlmClient.ModelList(true, result, null);
        } catch (SdkException e) {
            return new LlmClient.ModelList(false, List.of(),
                    "Bedrock-Modelle nicht abrufbar (Region " + r.id() + "): " + e.getMessage());
        }
    }

    static boolean isChatModel(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return !(lower.contains("embed") || lower.contains("rerank") || lower.contains("pegasus")
                || lower.contains("titan-image") || lower.contains("canvas") || lower.contains("reel"));
    }

    private BedrockRuntimeClient runtime(String region, int timeoutSec) {
        Region r = resolveRegion(region);
        return runtimeClients.computeIfAbsent(r.id() + "|" + timeoutSec, k -> BedrockRuntimeClient.builder()
                .region(r)
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .socketTimeout(Duration.ofSeconds(timeoutSec))
                        .connectionTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(timeoutSec))
                        // Wiederholungen steuert LlmClient selbst (llm.retryAttempts)
                        .retryStrategy(software.amazon.awssdk.retries.DefaultRetryStrategy.doNotRetry()))
                .build());
    }

    static Region resolveRegion(String region) {
        if (region != null && !region.isBlank()) return Region.of(region.trim());
        try {
            return new DefaultAwsRegionProviderChain().getRegion();
        } catch (SdkException e) {
            log.debug("Keine AWS-Region in der Umgebung - verwende eu-central-1");
            return Region.EU_CENTRAL_1;
        }
    }

    private static String regionName(String region) {
        return resolveRegion(region).id();
    }
}
