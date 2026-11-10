package bbbbot.stt.transcribe;

import bbbbot.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** ElevenLabs Scribe gegen einen lokalen Mock-Server: Anfrage, Antwort, Glossar, Wiederholung. */
class ElevenLabsTranscriberTest {

    private static final String RESPONSE = """
            {"language_code":"deu","language_probability":0.98,"text":"Hallo zusammen. Guten Morgen!",
             "words":[
              {"text":"Hallo","start":0.1,"end":0.5,"type":"word","speaker_id":"speaker_0","logprob":-0.1},
              {"text":" ","start":0.5,"end":0.6,"type":"spacing","speaker_id":"speaker_0","logprob":0},
              {"text":"zusammen.","start":0.6,"end":1.2,"type":"word","speaker_id":"speaker_0","logprob":-0.1},
              {"text":"(Lachen)","start":1.2,"end":1.8,"type":"audio_event","speaker_id":"speaker_0","logprob":0},
              {"text":"Guten","start":2.0,"end":2.3,"type":"word","speaker_id":"speaker_1","logprob":-0.1},
              {"text":" ","start":2.3,"end":2.4,"type":"spacing","speaker_id":"speaker_1","logprob":0},
              {"text":"Morgen!","start":2.4,"end":2.9,"type":"word","speaker_id":"speaker_1","logprob":-0.1}
             ]}
            """;

    private HttpServer server;
    private Path audio;
    private SettingsService settings;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> apiKeys = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        audio = Files.createTempFile("elevenlabs-test", ".mp3");
        Files.write(audio, "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8));

        settings = mock(SettingsService.class);
        when(settings.get(SettingsService.WHISPER_ELEVENLABS_URL))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/speech-to-text");
        when(settings.get(SettingsService.WHISPER_ELEVENLABS_API_KEY)).thenReturn("xi-test-key");
        when(settings.get(SettingsService.WHISPER_ELEVENLABS_MODEL)).thenReturn("scribe_v3");
        when(settings.getInt(anyString())).thenAnswer(inv -> switch (inv.getArgument(0, String.class)) {
            case SettingsService.WHISPER_TIMEOUT_SEC -> 10;
            case SettingsService.WHISPER_RETRY_ATTEMPTS -> 2;
            case SettingsService.WHISPER_ELEVENLABS_MAX_SPEAKERS -> 0;
            default -> 0;
        });
        when(settings.getLong(SettingsService.WHISPER_RETRY_BASE_MS)).thenReturn(1L);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop(0);
        Files.deleteIfExists(audio);
    }

    private void respond(int... statuses) {
        AtomicInteger call = new AtomicInteger();
        server.createContext("/v1/speech-to-text", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeys.add(exchange.getRequestHeaders().getFirst("xi-api-key"));
            queries.add(exchange.getRequestURI().getQuery());
            int status = statuses[Math.min(call.getAndIncrement(), statuses.length - 1)];
            byte[] body = (status == 200 ? RESPONSE : "{\"detail\":\"kaputt\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private ElevenLabsTranscriber transcriber(List<String> glossary) {
        return new ElevenLabsTranscriber(settings, () -> glossary);
    }

    @Test
    void schicktDateiUndFelderUndBautSaetzeJeSprecher() {
        respond(200);

        var result = transcriber(List.of()).transcribe(audio, "de", true);

        assertThat(result.success()).isTrue();
        assertThat(result.languageCode()).isEqualTo("deu");
        assertThat(result.utterances()).extracting(TranscribeResult.Utterance::speaker, TranscribeResult.Utterance::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SPEAKER_00", "Hallo zusammen."),
                        org.assertj.core.groups.Tuple.tuple("SPEAKER_01", "Guten Morgen!"));
        assertThat(apiKeys).containsExactly("xi-test-key");
        String body = bodies.getFirst();
        assertThat(body).contains("name=\"model_id\"\r\n\r\nscribe_v3")
                .contains("name=\"diarize\"\r\n\r\ntrue")
                .contains("name=\"language_code\"\r\n\r\nde")
                .contains("name=\"tag_audio_events\"\r\n\r\nfalse")
                .contains("filename=\"" + audio.getFileName() + "\"")
                .contains("fake-mp3-bytes")
                .doesNotContain("keyterms")
                .doesNotContain("num_speakers");
        assertThat(queries.getFirst()).isNull();
    }

    @Test
    void automatischeSpracheLaesstDenSprachcodeWeg() {
        respond(200);

        transcriber(List.of()).transcribe(audio, "", false);

        assertThat(bodies.getFirst()).doesNotContain("language_code").contains("name=\"diarize\"\r\n\r\nfalse");
    }

    @Test
    void glossarGehtAlsKeytermsMit() {
        when(settings.getBool(SettingsService.WHISPER_ELEVENLABS_KEYTERMS)).thenReturn(true);
        respond(200);

        transcriber(List.of("WIKAVIS", "Meeting Recorder", "kaputt[1]")).transcribe(audio, "de", false);

        assertThat(bodies.getFirst()).contains("name=\"keyterms\"\r\n\r\nWIKAVIS")
                .contains("name=\"keyterms\"\r\n\r\nMeeting Recorder")
                .doesNotContain("kaputt[1]");
    }

    @Test
    void nullRetentionSetztEnableLogging() {
        when(settings.getBool(SettingsService.WHISPER_ELEVENLABS_ZERO_RETENTION)).thenReturn(true);
        respond(200);

        transcriber(List.of()).transcribe(audio, "de", false);

        assertThat(queries.getFirst()).isEqualTo("enable_logging=false");
    }

    @Test
    void serverfehlerWirdWiederholtClientfehlerNicht() {
        respond(503, 200);
        assertThat(transcriber(List.of()).transcribe(audio, "de", false).success()).isTrue();
        assertThat(bodies).hasSize(2);
    }

    @Test
    void ungueltigeAnfrageScheitertOhneWiederholung() {
        respond(422);

        var result = transcriber(List.of()).transcribe(audio, "de", false);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ElevenLabs HTTP 422");
        assertThat(bodies).hasSize(1);
    }

    @Test
    void ohneApiKeyKeineAnfrage() {
        when(settings.get(SettingsService.WHISPER_ELEVENLABS_API_KEY)).thenReturn(" ");
        respond(200);

        var result = transcriber(List.of()).transcribe(audio, "de", false);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("whisper.elevenlabsApiKey");
        assertThat(bodies).isEmpty();
    }

    @Test
    void langePausenUndFehlendeWortlisteWerdenBehandelt() throws IOException {
        var mapper = new ObjectMapper();
        var paused = ElevenLabsTranscriber.parse(mapper.readTree("""
                {"words":[
                  {"text":"Erstens","start":0,"end":0.5,"type":"word"},
                  {"text":" ","type":"spacing"},
                  {"text":"zweitens","start":5.0,"end":5.5,"type":"word"}
                ]}"""));
        assertThat(paused).extracting(TranscribeResult.Utterance::text).containsExactly("Erstens", "zweitens");
        assertThat(paused.getFirst().speaker()).isNull();

        var textOnly = ElevenLabsTranscriber.parse(mapper.readTree("{\"text\":\" Nur Text \",\"words\":[]}"));
        assertThat(textOnly).extracting(TranscribeResult.Utterance::text).containsExactly("Nur Text");
    }

    @Test
    void sprachcodeUndKeytermGrenzen() {
        assertThat(ElevenLabsTranscriber.languageCode("pt-br")).isEqualTo("pt");
        assertThat(ElevenLabsTranscriber.languageCode("")).isNull();
        assertThat(ElevenLabsTranscriber.keyterms(List.of("eins zwei drei vier fünf sechs", "x".repeat(51),
                "  Doppelt  ", "Doppelt", "a<b"))).containsExactly("Doppelt");
    }
}
