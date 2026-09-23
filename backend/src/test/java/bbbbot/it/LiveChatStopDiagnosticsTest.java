package bbbbot.it;

import bbbbot.bot.BbbJoiner;
import bbbbot.bot.ChatOps;
import bbbbot.bot.CommandDetector;
import bbbbot.bot.SessionMarkers;
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

/**
 * Diagnose "STOPRECORDING im Chat wirkt nicht": Bleibt die Hinweisnachricht des
 * Bots (mit Marker) im DOM, wenn danach viele Nachrichten folgen? Nur dann
 * wertet die Befehlserkennung den Chat ueberhaupt aus.
 *
 * <p>Ein zweiter Browser-Kontext spielt den Teilnehmer, der den Befehl tippt.
 *
 * <pre>mvn test -Dtest=LiveChatStopDiagnosticsTest -Dbbb.it.url="https://.../apps/bbb/b/XYZ"</pre>
 */
@EnabledIfSystemProperty(named = "bbb.it.url", matches = ".+")
class LiveChatStopDiagnosticsTest {

    private static final int FILLER = Integer.getInteger("bbb.it.filler", 40);

    private Playwright playwright;
    private Browser browser;
    private BrowserContext botContext;
    private BrowserContext userContext;
    private DiagnosticsCapture diag;
    private final BbbJoiner joiner = new BbbJoiner();

    @BeforeEach
    void launch() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setArgs(List.of(
                "--autoplay-policy=no-user-gesture-required", "--no-sandbox",
                "--disable-dev-shm-usage", "--use-fake-ui-for-media-stream",
                "--use-fake-device-for-media-stream", "--ignore-certificate-errors")));
        botContext = newContext();
        userContext = newContext();
        diag = new DiagnosticsCapture(Path.of("target", "it-diag", "chat-stop"));
    }

    private BrowserContext newContext() {
        return browser.newContext(new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true).setViewportSize(1280, 720)
                .setPermissions(List.of("microphone")));
    }

    @AfterEach
    void teardown() {
        if (diag != null) diag.flush();
        try { if (browser != null) browser.close(); } catch (RuntimeException ignored) {}
        try { if (playwright != null) playwright.close(); } catch (RuntimeException ignored) {}
    }

    private static int domMessages(Page page) {
        Object n = page.evaluate("() => document.querySelectorAll('[data-test=\"chatMessageItem\"], [data-test^=\"chatMessage\"]').length");
        return n == null ? -1 : ((Number) n).intValue();
    }

    private void log(String msg) {
        System.out.println("[CHAT-DIAG] " + msg);
        System.out.flush();
        diag.note(msg);
    }

    private void report(String step, Page bot, ChatOps chat, String marker) {
        String all = chat.getAllChatText();
        CommandDetector.Detection det = CommandDetector.detectAfterMarker(all, marker, "STOPRECORDING", "STARTRECORDING");
        log(step + ": domNodes=" + domMessages(bot) + ", lines=" + all.split("\n").length
                + ", markerFound=" + det.markerFound() + ", foundStop=" + det.foundStop());
        log(step + " letzte Zeilen:\n  " + String.join("\n  ",
                List.of(all.split("\n")).subList(Math.max(0, all.split("\n").length - 4), all.split("\n").length)));
    }

    @Test
    void markerUeberlebtVieleNachrichten() {
        String url = System.getProperty("bbb.it.url");
        Page bot = botContext.newPage();
        log("Bot tritt bei...");
        joiner.join(bot, url, "RecorderBot-IT", 60_000, 60_000);
        log("Bot beigetreten");
        for (int i = 0; i < 6 && joiner.dismissModals(bot); i++) bot.waitForTimeout(500);
        Page user = userContext.newPage();
        joiner.join(user, url, "Teilnehmer-IT", 60_000, 60_000);
        log("Teilnehmer beigetreten");
        for (int i = 0; i < 6 && joiner.dismissModals(user); i++) user.waitForTimeout(500);

        ChatOps chat = new ChatOps(bot);
        ChatOps userChat = new ChatOps(user);
        String marker = SessionMarkers.generate();
        chat.sendMessage("Diese Sitzung wird aufgezeichnet. Mit STOPRECORDING verwerfen. [" + marker + "]");
        bot.waitForTimeout(1500);
        report("nach-Marker", bot, chat, marker);

        for (int i = 1; i <= FILLER; i++) {
            (i % 2 == 0 ? chat : userChat).sendMessage("Fuelltext " + i);
            bot.waitForTimeout(250);
            if (i % 10 == 0) report("nach-" + i + "-Fuellern", bot, chat, marker);
        }
        diag.capture(bot, "nach-fuellern");

        userChat.sendMessage("STOPRECORDING");
        bot.waitForTimeout(2000);
        report("nach-STOP", bot, chat, marker);
        diag.capture(bot, "nach-stop");
    }

    /**
     * Mehrere Nachrichten desselben Teilnehmers kurz hintereinander: BBB fasst
     * sie optisch zusammen. Liest die Erkennung dann noch jede einzelne?
     */
    @Test
    void aufeinanderfolgendeNachrichtenDesselbenTeilnehmers() {
        String url = System.getProperty("bbb.it.url");
        Page bot = botContext.newPage();
        joiner.join(bot, url, "RecorderBot-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(bot); i++) bot.waitForTimeout(500);
        Page user = userContext.newPage();
        joiner.join(user, url, "Teilnehmer-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(user); i++) user.waitForTimeout(500);
        log("beide beigetreten");

        ChatOps chat = new ChatOps(bot);
        ChatOps userChat = new ChatOps(user);
        String marker = SessionMarkers.generate();
        chat.sendMessage("Hinweis STOPRECORDING [" + marker + "]");
        bot.waitForTimeout(1000);
        userChat.sendMessage("Hallo zusammen");
        userChat.sendMessage("noch was");
        userChat.sendMessage("STOPRECORDING");
        bot.waitForTimeout(2500);
        report("gruppiert", bot, chat, marker);
        Object structure = bot.evaluate("""
                () => Array.from(document.querySelectorAll('[data-test="chatMessageItem"]')).slice(-3).map(el =>
                    Array.from(el.querySelectorAll('[data-test]')).map(c => c.getAttribute('data-test')).join(',')
                    + ' | text=' + el.innerText.replace(/\\s+/g, ' ').slice(0, 120))""");
        log("Struktur der letzten Items: " + structure);
        Object allTests = bot.evaluate("""
                () => Array.from(new Set(Array.from(document.querySelectorAll('[data-test]'))
                    .map(e => e.getAttribute('data-test')).filter(t => /chat|message/i.test(t))))""");
        log("data-test-Werte im Chat: " + allTests);
        diag.capture(bot, "gruppiert");
    }

    /**
     * Gegenstueck zu einem ECHTEN Bot (vorher per API gestartet): Der Teilnehmer
     * wartet auf die Aufnahme-Hinweisnachricht des Bots und tippt dann den
     * STOP-Befehl. Ob der Bot reagiert, zeigt sein Log bzw. seine Antwort im Chat.
     */
    @Test
    @EnabledIfSystemProperty(named = "bbb.it.participantOnly", matches = "true")
    void teilnehmerTipptStopGegenEchtenBot() {
        String url = System.getProperty("bbb.it.url");
        String command = System.getProperty("bbb.it.command", "STOPRECORDING");
        Page user = userContext.newPage();
        joiner.join(user, url, "Teilnehmer-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(user); i++) user.waitForTimeout(500);
        ChatOps userChat = new ChatOps(user);
        userChat.ensureChatOpen();
        log("Teilnehmer im Raum, warte auf Hinweis des Bots...");
        long deadline = System.currentTimeMillis() + 180_000;
        int before = userChat.getAllChatText().split("\n").length;
        boolean seen = false;
        while (System.currentTimeMillis() < deadline) {
            String[] lines = userChat.getAllChatText().split("\n");
            for (int i = before; i < lines.length; i++) {
                if (lines[i].contains("[REC")) { seen = true; log("Hinweis gesehen: " + lines[i]); }
            }
            if (seen) break;
            user.waitForTimeout(2000);
        }
        log("Hinweis gesehen=" + seen + " - tippe '" + command + "'");
        user.waitForTimeout(3000);
        userChat.sendMessage(command);
        user.waitForTimeout(25_000);
        String[] lines = userChat.getAllChatText().split("\n");
        log("Chat danach (letzte 5):\n  " + String.join("\n  ",
                List.of(lines).subList(Math.max(0, lines.length - 5), lines.length)));
    }

    /** DOM-Struktur der Chat-Nachrichten: Wo stehen Absender, Zeit, eigene Nachricht? */
    @Test
    @EnabledIfSystemProperty(named = "bbb.it.dumpDom", matches = "true")
    void chatDomStruktur() {
        String url = System.getProperty("bbb.it.url");
        Page bot = botContext.newPage();
        joiner.join(bot, url, "RecorderBot-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(bot); i++) bot.waitForTimeout(500);
        Page user = userContext.newPage();
        joiner.join(user, url, "Teilnehmer-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(user); i++) user.waitForTimeout(500);
        ChatOps chat = new ChatOps(bot);
        ChatOps userChat = new ChatOps(user);
        chat.sendMessage("Bot-Nachricht eins");
        chat.sendMessage("Bot-Nachricht zwei");
        userChat.sendMessage("Teilnehmer-Nachricht eins");
        userChat.sendMessage("Teilnehmer-Nachricht zwei");
        chat.sendMessage("Bot-Nachricht drei");
        bot.waitForTimeout(2500);
        Object html = bot.evaluate("""
                () => Array.from(document.querySelectorAll('[data-test="chatMessageItem"]')).slice(-5)
                    .map(el => el.outerHTML.replace(/<svg[\\s\\S]*?<\\/svg>/g, '<svg/>').slice(0, 3000)).join('\\n=====\\n')""");
        log("BOT-SICHT:\n" + html);
        Object userHtml = user.evaluate("""
                () => Array.from(document.querySelectorAll('[data-test="chatMessageItem"]')).slice(-5)
                    .map(el => el.outerHTML.replace(/<svg[\\s\\S]*?<\\/svg>/g, '<svg/>').slice(0, 1500)).join('\\n=====\\n')""");
        log("TEILNEHMER-SICHT:\n" + userHtml);
    }

    /**
     * Eigene Nachrichten des Bots werden ignoriert: Ein STOP-Befehl, den der Bot
     * selbst schreibt, loest nichts aus, und im Chat-Protokoll stehen nur die
     * Nachrichten der Teilnehmer.
     */
    @Test
    void eigeneNachrichtenWerdenIgnoriert() {
        String url = System.getProperty("bbb.it.url");
        Page bot = botContext.newPage();
        joiner.join(bot, url, "RecorderBot-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(bot); i++) bot.waitForTimeout(500);
        Page user = userContext.newPage();
        joiner.join(user, url, "Teilnehmer-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(user); i++) user.waitForTimeout(500);
        ChatOps chat = new ChatOps(bot);
        ChatOps userChat = new ChatOps(user);

        String marker = SessionMarkers.generate();
        chat.sendMessage("Hinweis: Mit STOPRECORDING verwerfen. [" + marker + "]");
        chat.sendMessage("STOPRECORDING");
        userChat.sendMessage("Wortmeldung des Teilnehmers");
        chat.sendMessage("Noch eine Bot-Nachricht");
        bot.waitForTimeout(2500);

        CommandDetector.Detection eigenesStop = CommandDetector.detectAfterMarker(
                chat.getAllChatText(), marker, "STOPRECORDING", "STARTRECORDING");
        log("eigenes STOP: markerFound=" + eigenesStop.markerFound() + ", foundStop=" + eigenesStop.foundStop());
        org.junit.jupiter.api.Assertions.assertTrue(eigenesStop.markerFound(), "Marker nicht gefunden");
        org.junit.jupiter.api.Assertions.assertFalse(eigenesStop.foundStop(), "Eigenes STOP hat ausgeloest");

        String protokoll = chat.getChatSinceMarker(marker, 3, 500);
        log("Chat-Protokoll: " + protokoll.replace("\n", " | "));
        org.junit.jupiter.api.Assertions.assertTrue(protokoll.contains("Wortmeldung des Teilnehmers"));
        org.junit.jupiter.api.Assertions.assertFalse(protokoll.contains("Bot-Nachricht")
                || protokoll.contains("STOPRECORDING") || protokoll.contains(marker), protokoll);

        userChat.sendMessage("STOPRECORDING");
        bot.waitForTimeout(2500);
        CommandDetector.Detection fremdesStop = CommandDetector.detectAfterMarker(
                chat.getAllChatText(), marker, "STOPRECORDING", "STARTRECORDING");
        log("STOP des Teilnehmers: foundStop=" + fremdesStop.foundStop());
        org.junit.jupiter.api.Assertions.assertTrue(fremdesStop.foundStop(), "STOP des Teilnehmers nicht erkannt");
    }

    /**
     * Anonymer Stopp-Link gegen einen ECHTEN Bot (vorher per API gestartet, mit
     * eingeschaltetem bot.anonymousStopEnabled): Teilnehmer liest den Link aus
     * dem Hinweis, oeffnet ihn, bestaetigt - danach muss der Bot den Raum
     * verlassen und ein zweiter Aufruf des Links ungueltig sein.
     */
    @Test
    @EnabledIfSystemProperty(named = "bbb.it.stopLink", matches = "true")
    void anonymerStoppLinkGegenEchtenBot() {
        String url = System.getProperty("bbb.it.url");
        Page user = userContext.newPage();
        joiner.join(user, url, "Teilnehmer-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(user); i++) user.waitForTimeout(500);
        ChatOps userChat = new ChatOps(user);
        userChat.ensureChatOpen();
        log("Teilnehmer im Raum, warte auf Hinweis mit Stopp-Link...");
        java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile("https?://\\S+/stop/[A-Za-z0-9_-]+");
        String link = null;
        long deadline = System.currentTimeMillis() + 180_000;
        while (link == null && System.currentTimeMillis() < deadline) {
            for (ChatOps.ChatEntry e : userChat.readEntries()) {
                java.util.regex.Matcher m = linkPattern.matcher(e.text());
                if (m.find()) { link = m.group(); log("Hinweis: " + e.text()); }
            }
            if (link == null) user.waitForTimeout(2000);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(link, "Kein Stopp-Link im Chat");
        log("Stopp-Link: " + link);

        Page stopPage = userContext.newPage();
        stopPage.navigate(link);
        stopPage.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(java.util.regex.Pattern.compile("verwerfen|discard", java.util.regex.Pattern.CASE_INSENSITIVE))).click();
        log("Seite: " + stopPage.locator(".card").innerText().replace("\n", " | "));
        stopPage.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(java.util.regex.Pattern.compile("Ja, Aufnahme beenden|Yes, stop recording"))).click();
        stopPage.waitForSelector(".alert-success", new Page.WaitForSelectorOptions().setTimeout(15_000));
        log("Nach Bestaetigung: " + stopPage.locator(".card").innerText().replace("\n", " | "));
        diag.capture(stopPage, "stop-seite");

        user.waitForTimeout(15_000);
        String[] lines = userChat.getAllChatText().split("\n");
        log("Chat danach (letzte 3):\n  " + String.join("\n  ",
                List.of(lines).subList(Math.max(0, lines.length - 3), lines.length)));

        stopPage.navigate(link);
        stopPage.waitForTimeout(3000);
        log("Zweiter Aufruf: " + stopPage.locator(".card").innerText().replace("\n", " | "));
    }

    /** Woher kommen doppelte Nachrichten? Container und ID jeder Nachricht. */
    @Test
    @EnabledIfSystemProperty(named = "bbb.it.dumpDom", matches = "true")
    void doppelteNachrichten() {
        String url = System.getProperty("bbb.it.url");
        Page bot = botContext.newPage();
        joiner.join(bot, url, "RecorderBot-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(bot); i++) bot.waitForTimeout(500);
        Page user = userContext.newPage();
        joiner.join(user, url, "Teilnehmer-IT", 60_000, 60_000);
        for (int i = 0; i < 6 && joiner.dismissModals(user); i++) user.waitForTimeout(500);
        new ChatOps(user).ensureChatOpen();
        new ChatOps(bot).sendMessage("Doppel-Test " + System.nanoTime());
        user.waitForTimeout(3000);
        Object info = user.evaluate("""
                () => Array.from(document.querySelectorAll('[data-test="chatMessageItem"]')).slice(-4).map(el => {
                  const path = [];
                  for (let p = el.parentElement; p && path.length < 6; p = p.parentElement) {
                    path.push((p.getAttribute('data-test') || p.id || p.getAttribute('role') || p.tagName).toString());
                  }
                  return el.getAttribute('data-chat-message-id') + ' <- ' + path.join(' < ')
                    + ' | ' + (el.innerText || '').slice(0, 40).replace(/\\s+/g, ' ');
                })""");
        log("Items: " + String.valueOf(info).replace("], ", "]\n"));
    }
}
