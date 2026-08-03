package bbbbot.bot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Laedt die in die BBB-Seite injizierten JavaScript-Snippets aus den Ressourcen. */
public final class BrowserScripts {

    public static final String RECORDER = "bot/recorder.js";
    public static final String CHAT_TEXT = "bot/chatText.js";
    public static final String CHAT_MESSAGES = "bot/chatMessages.js";
    public static final String ATTENDEES = "bot/attendees.js";
    public static final String AUDIO_STATS = "bot/audioStats.js";
    public static final String HAS_REMOTE_AUDIO = "bot/hasRemoteAudio.js";
    public static final String START_COMMAND_INFO = "bot/startCommandInfo.js";
    public static final String ROOM_NAME = "bot/roomName.js";

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private BrowserScripts() {}

    public static String load(String resource) {
        return CACHE.computeIfAbsent(resource, r -> {
            try (InputStream in = BrowserScripts.class.getClassLoader().getResourceAsStream(r)) {
                if (in == null) throw new IllegalStateException("Skript-Ressource fehlt: " + r);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
