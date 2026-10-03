package bbbbot.stt;

import bbbbot.settings.SettingsService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests fuer den OpenAI-kompatiblen Cloud-Provider gegen einen lokalen Mock-Server. */
class WhisperClientOpenAiTest {

    private HttpServer server;
    private Path audioFile;
    private SettingsService settings;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        audioFile = Files.createTempFile("whisper-test", ".mp3");
        Files.write(audioFile, "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8));

        settings = mock(SettingsService.class);
        when(settings.get(SettingsService.WHISPER_PROVIDER)).thenReturn("openai");
        when(settings.get(SettingsService.WHISPER_OPENAI_URL))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/audio/transcriptions");
        when(settings.get(SettingsService.WHISPER_OPENAI_API_KEY)).thenReturn("sk-test-key");
        when(settings.get(SettingsService.WHISPER_OPENAI_MODEL)).thenReturn("whisper-1");
        when(settings.get(SettingsService.WHISPER_OPENAI_DIARIZE_MODEL)).thenReturn("gpt-4o-transcribe-diarize");
        when(settings.get(SettingsService.WHISPER_LANGUAGE)).thenReturn("de");
        when(settings.get(SettingsService.WHISPER_INITIAL_PROMPT)).thenReturn("");
        when(settings.getInt(anyString())).thenAnswer(inv -> switch (inv.getArgument(0, String.class)) {
            case SettingsService.WHISPER_TIMEOUT_SEC -> 10;
            case SettingsService.WHISPER_RETRY_ATTEMPTS -> 1;
            default -> 0;
        });
        when(settings.getLong(SettingsService.WHISPER_RETRY_BASE_MS)).thenReturn(1L);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop(0);
        Files.deleteIfExists(audioFile);
    }

    @Test
    void sendetMultipartMitBearerTokenUndParstVerboseJson() {
        List<String> authHeaders = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        server.createContext("/v1/audio/transcriptions", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"text":"Hallo Welt. Zweiter Satz.","segments":[
                      {"start":0.0,"end":2.0,"text":"Hallo Welt."},
                      {"start":65.0,"end":67.0,"text":"Zweiter Satz."}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        var result = new WhisperClient(settings).transcribe(audioFile, false);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("[00:00] Hallo Welt.\n[01:05] Zweiter Satz.");
        assertThat(authHeaders).containsExactly("Bearer sk-test-key");
        assertThat(bodies.get(0))
                .contains("name=\"model\"").contains("whisper-1")
                .contains("name=\"response_format\"").contains("verbose_json")
                .contains("name=\"language\"")
                .contains("name=\"file\"").contains("fake-mp3-bytes");
    }

    @Test
    void spracheDerAufnahmeSchlaegtDenAdminStandard() {
        List<String> bodies = respondWithText();

        var result = new WhisperClient(settings).transcribe(audioFile, false, "en");

        assertThat(result.success()).isTrue();
        assertThat(languageField(bodies.get(0))).isEqualTo("en");
    }

    @Test
    void automatischErkennenLaesstDieSprachvorgabeWeg() {
        List<String> bodies = respondWithText();

        var result = new WhisperClient(settings).transcribe(audioFile, false, SttLanguage.AUTO);

        assertThat(result.success()).isTrue();
        assertThat(bodies.get(0)).doesNotContain("name=\"language\"");
    }

    /** Mock-Antwort ohne Segmente; liefert die empfangenen Anfrage-Koerper. */
    private List<String> respondWithText() {
        List<String> bodies = new ArrayList<>();
        server.createContext("/v1/audio/transcriptions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"text\":\"Hello world.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return bodies;
    }

    /** Wert des multipart-Feldes "language" aus dem Anfrage-Koerper. */
    private static String languageField(String body) {
        var matcher = java.util.regex.Pattern
                .compile("name=\"language\"\r\n\r\n([^\r]*)\r\n")
                .matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Test
    void faelltBeiUnbekanntemResponseFormatAufJsonZurueck() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/audio/transcriptions", exchange -> {
            byte[] response = calls.incrementAndGet() == 1
                    ? "{\"error\":{\"message\":\"response_format 'verbose_json' not supported\"}}"
                            .getBytes(StandardCharsets.UTF_8)
                    : "{\"text\":\"Nur Text ohne Zeitstempel.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(calls.get() == 1 ? 400 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        var result = new WhisperClient(settings).transcribe(audioFile, false);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("Nur Text ohne Zeitstempel.");
    }

    @Test
    void meldetFehlendenApiKeyVerstaendlich() {
        when(settings.get(SettingsService.WHISPER_OPENAI_API_KEY)).thenReturn("");

        var result = new WhisperClient(settings).transcribe(audioFile, false);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("whisper.openaiApiKey");
    }

    private static final String DIARIZED_RESPONSE = """
            {"text":"...","segments":[
              {"speaker":"A","start":0.0,"end":4.0,"text":"Hallo, ich bin Anna."},
              {"speaker":"B","start":4.5,"end":9.0,"text":"Und ich bin Tom."},
              {"speaker":"A","start":65.0,"end":66.0,"text":"Schoen."}]}
            """;

    @Test
    void sprechererkennungNutztDasDiarisierungsModellUndStabileLabels() {
        List<String> bodies = respondWith(DIARIZED_RESPONSE);

        var result = new WhisperClient(settings).transcribe(audioFile, true, null, new WhisperClient.SpeakerContext());

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("""
                SPEAKER_00:
                [00:00] Hallo, ich bin Anna.

                SPEAKER_01:
                [00:04] Und ich bin Tom.

                SPEAKER_00:
                [01:05] Schoen.""");
        assertThat(bodies.get(0))
                .contains("gpt-4o-transcribe-diarize")
                .contains("diarized_json")
                .contains("name=\"chunking_strategy\"")
                .doesNotContain("name=\"prompt\"");
    }

    @Test
    void folgesegmenteBekommenStimmprobenDerBekanntenSprecher() throws IOException {
        List<String> bodies = respondWith(DIARIZED_RESPONSE);
        Path clip = Files.createTempFile("clip", ".wav");
        Files.write(clip, "RIFF-fake-wav".getBytes(StandardCharsets.UTF_8));
        List<Double> clipLengths = new ArrayList<>();
        WhisperClient client = new WhisperClient(settings, (source, start, length, wav) -> {
            clipLengths.add(length);
            try {
                Files.copy(clip, wav, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return wav;
        });
        var speakers = new WhisperClient.SpeakerContext();

        client.transcribe(audioFile, true, null, speakers);
        var second = client.transcribe(audioFile, true, null, speakers);

        // Je neuem Sprecher die laengste Aeusserung von 2-10 s als Probe - bis zur
        // Obergrenze von 4 bekannten Sprechern je Anfrage.
        assertThat(clipLengths).containsExactly(4.0, 4.5, 4.0, 4.5);
        assertThat(bodies.get(0)).doesNotContain("known_speaker_names");
        assertThat(bodies.get(1))
                .contains("name=\"known_speaker_names[]\"\r\n\r\nSPEAKER_00")
                .contains("name=\"known_speaker_names[]\"\r\n\r\nSPEAKER_01")
                .contains("data:audio/wav;base64,");
        // Die Antwort nennt weiterhin A/B (unser Mock) - neue Stimmen bekommen
        // fortlaufende Labels statt wieder bei SPEAKER_00 anzufangen.
        assertThat(second.text()).contains("SPEAKER_02:").contains("SPEAKER_03:");
        Files.deleteIfExists(clip);
    }

    @Test
    void abgelehnteSprechererkennungFaelltAufTranskriptOhneSprecherZurueck() {
        List<String> bodies = new ArrayList<>();
        server.createContext("/v1/audio/transcriptions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            boolean diarizeRequest = body.contains("diarized_json");
            byte[] response = (diarizeRequest
                    ? "{\"error\":{\"message\":\"model not found\"}}"
                    : "{\"text\":\"Ohne Sprecher.\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(diarizeRequest ? 400 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        var result = new WhisperClient(settings).transcribe(audioFile, true);

        assertThat(bodies).hasSize(2);
        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("Ohne Sprecher.");
    }

    /** Mock-Server antwortet immer mit {@code json}; liefert die Anfrage-Koerper. */
    private List<String> respondWith(String json) {
        List<String> bodies = new ArrayList<>();
        server.createContext("/v1/audio/transcriptions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return bodies;
    }
}
