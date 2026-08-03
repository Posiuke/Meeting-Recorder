package bbbbot.it;

import bbbbot.bot.BbbJoiner;
import bbbbot.bot.ChatOps;
import bbbbot.bot.PageAudioRecorder;
import bbbbot.bot.ParticipantOps;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-End-Funktionstest gegen einen echten BBB-Raum: Join, Teilnehmerliste,
 * Audio-Tracks, Modal-Handling, Chat senden/lesen, Befehls-Erkennung und
 * Browser-Audioaufnahme inkl. Segment-Rotation.
 *
 * Aktivierung:
 *   mvn test -Dtest=LiveRoomFunctionsTest -Dbbb.it.url="https://.../apps/bbb/b/XYZ"
 */
@EnabledIfSystemProperty(named = "bbb.it.url", matches = ".+")
class LiveRoomFunctionsTest {

    private static final String BOT_NAME = System.getProperty("bbb.it.name", "RecorderBot-IT");
    private static final String KEEPALIVE_PREFIX = "~KA~";

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private DiagnosticsCapture diag;
    private final BbbJoiner joiner = new BbbJoiner();

    @BeforeEach
    void launchLikeProduction() {
        playwright = Playwright.create();
        List<String> args = List.of(
                "--autoplay-policy=no-user-gesture-required",
                "--no-sandbox",
                "--disable-features=AudioServiceOutOfProcess",
                "--disable-dev-shm-usage",
                "--use-fake-ui-for-media-stream",
                "--use-fake-device-for-media-stream",
                "--ignore-certificate-errors",
                "--allow-insecure-localhost");
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true).setArgs(args));
        context = browser.newContext(new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setViewportSize(1280, 720)
                .setPermissions(List.of("microphone")));
        page = context.newPage();
        diag = new DiagnosticsCapture(Path.of("target", "it-diag", "functions"));
        diag.attachConsole(page);
    }

    @AfterEach
    void teardown() {
        if (diag != null) diag.flush();
        try { if (page != null) page.close(); } catch (RuntimeException ignored) {}
        try { if (context != null) context.close(); } catch (RuntimeException ignored) {}
        try { if (browser != null) browser.close(); } catch (RuntimeException ignored) {}
        try { if (playwright != null) playwright.close(); } catch (RuntimeException ignored) {}
    }

    @Test
    void botFunctionsEndToEnd() {
        String url = System.getProperty("bbb.it.url");
        joiner.join(page, url, BOT_NAME, 60_000, 60_000);
        diag.capture(page, "joined");

        // --- Teilnehmerliste: Bot muss sich selbst sehen -------------------
        ParticipantOps participants = new ParticipantOps(page);
        ParticipantOps.AttendeeInfo info = participants.getAttendeeInfo(BOT_NAME);
        diag.note("Teilnehmer: total=" + info.total() + ", others=" + info.others() + ", names=" + info.names());
        assertTrue(info.total() >= 1, "Teilnehmerliste leer");
        assertTrue(participants.isBotPresent(BOT_NAME, info), "Bot erkennt sich nicht in der Teilnehmerliste: " + info.names());

        // --- Remote-Audio-Tracks -------------------------------------------
        int tracks = participants.remoteAudioTrackCount();
        diag.note("Remote-Audio-Tracks: " + tracks);
        assertTrue(tracks >= 1, "Kein Remote-Audio-Track nach Join");

        // --- Raumnamen-Erkennung -------------------------------------------
        Object roomName = page.evaluate(bbbbot.bot.BrowserScripts.load(bbbbot.bot.BrowserScripts.ROOM_NAME));
        diag.note("Erkannter Raumname: '" + roomName + "'");
        assertTrue(roomName != null && !roomName.toString().isBlank(),
                "Raumname konnte nicht aus der BBB-Oberflaeche ermittelt werden");

        // --- Modal-Handling: Info-Modals schliessen, Audio bleibt ----------
        for (int i = 0; i < 6; i++) {
            if (!joiner.dismissModals(page)) break;
            page.waitForTimeout(500);
        }
        diag.capture(page, "modals-dismissed");
        Object sessionDetailsVisible = page.evaluate("""
                () => Array.from(document.querySelectorAll('[aria-label]'))
                    .some(el => /session details|sitzungsdetails/i.test(el.getAttribute('aria-label') || '')
                        && el.getBoundingClientRect().width > 0)""");
        diag.note("Session-Details-Modal noch sichtbar: " + sessionDetailsVisible);
        assertTrue(Boolean.FALSE.equals(sessionDetailsVisible), "Session-Details-Modal wurde nicht geschlossen");
        assertTrue(participants.remoteAudioTrackCount() >= 1, "Remote-Audio nach Modal-Dismiss verloren");

        // --- Chat: senden, lesen, Marker-Extraktion ------------------------
        ChatOps chat = new ChatOps(page);
        String marker = "IT-MARKER-" + Long.toHexString(System.nanoTime());
        chat.sendMessage("Aufzeichnungshinweis (Test) [" + marker + "]");
        page.waitForTimeout(1_500);
        chat.sendMessage("Nachricht nach Marker");
        page.waitForTimeout(1_500);

        String allChat = chat.getAllChatText();
        diag.note("getAllChatText length=" + allChat.length());
        assertTrue(allChat.contains(marker), "Gesendeter Marker nicht im Chat-Text gefunden");

        List<String> messages = chat.extractMessages(KEEPALIVE_PREFIX);
        diag.note("extractMessages: " + messages.size() + " Nachrichten");
        assertTrue(messages.stream().anyMatch(m -> m.contains(marker)), "Marker nicht in extractMessages");

        String sinceMarker = chat.getChatSinceMarker(marker, 3, 500, KEEPALIVE_PREFIX);
        diag.note("getChatSinceMarker: " + sinceMarker.replace('\n', ' '));
        assertTrue(sinceMarker.contains("Nachricht nach Marker"), "Chat nach Marker unvollstaendig: '" + sinceMarker + "'");

        // --- START-Befehls-Erkennung ---------------------------------------
        String startCommand = "!aufnahme-start-test";
        chat.sendMessage(startCommand);
        page.waitForTimeout(1_500);
        ChatOps.StartCommandInfo cmd = chat.detectStartCommandWithInfo(startCommand);
        diag.note("StartCommand erkannt: " + cmd.found() + " preview=" + cmd.messagePreview());
        assertTrue(cmd.found(), "START-Befehl im Chat nicht erkannt");

        // --- Audioaufnahme mit Segment-Rotation ----------------------------
        PageAudioRecorder recorder = new PageAudioRecorder(page);
        AtomicLong totalBytes = new AtomicLong();
        AtomicInteger lastChunks = new AtomicInteger();
        recorder.start(5_000, (data, isLast) -> {
            totalBytes.addAndGet(data.length);
            if (isLast) lastChunks.incrementAndGet();
        });
        page.waitForTimeout(12_000);
        recorder.stop();
        long deadline = System.currentTimeMillis() + 8_000;
        while (lastChunks.get() < 3 && System.currentTimeMillis() < deadline) {
            page.waitForTimeout(250);
        }
        recorder.clearSink();
        diag.note("Aufnahme: bytes=" + totalBytes.get() + ", lastChunks=" + lastChunks.get());
        assertTrue(totalBytes.get() > 0, "Keine Audio-Daten aufgenommen");
        assertTrue(lastChunks.get() >= 2, "Segment-Rotation hat nicht stattgefunden (lastChunks=" + lastChunks.get() + ")");

        diag.capture(page, "functions-done");
        diag.note("Alle Funktionstests erfolgreich.");
    }
}
