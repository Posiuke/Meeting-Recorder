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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprachwahl beim lokalen Whisper-ASR-Webservice: Die Sprache der Aufnahme geht
 * vor dem Admin-Standard, "automatisch erkennen" laesst den Parameter ganz weg.
 */
class WhisperClientLocalLanguageTest {

    private HttpServer server;
    private Path audioFile;
    private SettingsService settings;
    private final List<String> queries = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asr", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"text\":\"Hello world.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        audioFile = Files.createTempFile("whisper-local-test", ".mp3");
        Files.write(audioFile, "fake-mp3-bytes".getBytes(StandardCharsets.UTF_8));

        settings = mock(SettingsService.class);
        when(settings.get(SettingsService.WHISPER_PROVIDER)).thenReturn("local");
        when(settings.get(SettingsService.WHISPER_URL))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/asr");
        when(settings.get(SettingsService.WHISPER_LANGUAGE)).thenReturn("de");
        when(settings.get(SettingsService.WHISPER_OUTPUT)).thenReturn("json");
        when(settings.get(SettingsService.WHISPER_INITIAL_PROMPT)).thenReturn("");
        when(settings.getBool(SettingsService.WHISPER_VAD_FILTER)).thenReturn(true);
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
    void ohneWahlGiltDerAdminStandard() {
        assertThat(new WhisperClient(settings).transcribe(audioFile, false, null).success()).isTrue();
        assertThat(queries.get(0)).contains("language=de");
    }

    @Test
    void spracheDerAufnahmeSchlaegtDenAdminStandard() {
        assertThat(new WhisperClient(settings).transcribe(audioFile, false, "en").success()).isTrue();
        assertThat(queries.get(0)).contains("language=en").doesNotContain("language=de");
    }

    @Test
    void automatischErkennenLaesstDenParameterWeg() {
        assertThat(new WhisperClient(settings).transcribe(audioFile, false, SttLanguage.AUTO).success()).isTrue();
        assertThat(queries.get(0)).doesNotContain("language=");
    }
}
