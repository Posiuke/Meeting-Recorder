package bbbbot.it;

import bbbbot.bot.BbbJoiner;
import bbbbot.bot.BrowserScripts;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-Integrationstest gegen einen echten BBB-Raum (Nextcloud-BBB-URL).
 *
 * Standardmaessig uebersprungen; Aktivierung ueber:
 *   mvn test -Dtest=LiveRoomJoinDiagnosticsTest -Dbbb.it.url="https://.../apps/bbb/b/XYZ"
 *
 * exploreJoinFlow geht den Join-Weg Schritt fuer Schritt instrumentiert und
 * legt Screenshots + DOM-Inventar unter target/it-diag/ ab - damit laesst sich
 * ohne Zuschauen feststellen, an welcher Stelle (z.B. Audio-Auswahl) der
 * Produktions-Flow haengt und welche Selektoren die BBB-Version tatsaechlich hat.
 */
@EnabledIfSystemProperty(named = "bbb.it.url", matches = ".+")
class LiveRoomJoinDiagnosticsTest {

    private static final String BOT_NAME = System.getProperty("bbb.it.name", "RecorderBot-IT");

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private DiagnosticsCapture diag;

    private static String roomUrl() {
        return System.getProperty("bbb.it.url");
    }

    @BeforeEach
    void launchLikeProduction() {
        playwright = Playwright.create();
        // Identische Argumente wie BotInstance.launchBrowserAndJoin(), damit der
        // Test das Produktionsverhalten reproduziert.
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
                .setHeadless(true)
                .setArgs(args));
        context = browser.newContext(new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setViewportSize(1280, 720)
                .setPermissions(List.of("microphone")));
        page = context.newPage();
    }

    @AfterEach
    void teardown() {
        if (diag != null) diag.flush();
        try { if (page != null) page.close(); } catch (RuntimeException ignored) {}
        try { if (context != null) context.close(); } catch (RuntimeException ignored) {}
        try { if (browser != null) browser.close(); } catch (RuntimeException ignored) {}
        try { if (playwright != null) playwright.close(); } catch (RuntimeException ignored) {}
    }

    // ------------------------------------------------------------ Explorer

    @Test
    void exploreJoinFlow() {
        diag = new DiagnosticsCapture(Path.of("target", "it-diag", "explore"));
        diag.attachConsole(page);

        diag.note("Navigiere zu " + roomUrl());
        page.navigate(roomUrl(), new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(60_000));
        diag.capture(page, "landing");

        // Namensfeld (Nextcloud-BBB-Formular)
        Locator nameField = firstVisible(List.of(
                "#displayname",
                "input[name=\"displayname\"]",
                "input[placeholder*=\"Name\" i]",
                "input[type=\"text\"]"));
        if (nameField != null) {
            nameField.fill(BOT_NAME);
            diag.capture(page, "name-filled");
            Locator submit = firstVisible(List.of(
                    "#displayname-submit", "input[type=\"submit\"]", "button[type=\"submit\"]"));
            if (submit != null) {
                submit.click();
                diag.note("Namens-Submit geklickt.");
            } else {
                nameField.press("Enter");
                diag.note("Kein Submit gefunden, Enter gedrueckt.");
            }
        } else {
            diag.note("Kein Namensfeld gefunden.");
        }

        // Auf Audio-Auswahl bzw. Meeting-UI warten und dabei regelmaessig festhalten
        boolean audioStageSeen = false;
        for (int i = 0; i < 12; i++) {
            page.waitForTimeout(2_500);
            diag.capture(page, "wait-" + i);
            String matched = matchedKnownSelectors();
            diag.note("Tick " + i + " - bekannte Selektoren: " + matched + " | URL=" + page.url());
            if (!matched.isEmpty()) {
                audioStageSeen = true;
                break;
            }
        }
        diag.capture(page, "audio-stage");

        // Aktuelle Produktions-Strategie nachspielen: erst Listen-Only, dann Join
        Locator listenOnly = firstVisible(List.of(
                "button[data-test=\"listenOnlyBtn\"]",
                "button[aria-label*=\"Nur zuhören\" i]",
                "button:has-text(\"Nur zuhören\")",
                "button:has-text(\"Listen only\")"));
        if (listenOnly != null) {
            diag.note("Listen-Only-Button gefunden - klicke.");
            listenOnly.click();
        } else {
            diag.note("KEIN Listen-Only-Button gefunden. Pruefe Mikrofon-/Join-Alternativen.");
            Locator micBtn = firstVisible(List.of(
                    "button[data-test=\"microphoneBtn\"]",
                    "button[aria-label*=\"Mikrofon\" i]",
                    "button[aria-label*=\"Microphone\" i]"));
            if (micBtn != null) {
                diag.note("Mikrofon-Button gefunden - klicke (Echo-Test erwartet).");
                micBtn.click();
                page.waitForTimeout(4_000);
                diag.capture(page, "after-mic-click");
                Locator echoYes = firstVisible(List.of(
                        "button[data-test=\"echoYesBtn\"]",
                        "button[aria-label*=\"Echo\" i]",
                        "button:has-text(\"Ja\")", "button:has-text(\"Yes\")"));
                if (echoYes != null) {
                    diag.note("Echo-Test-Bestaetigung gefunden - klicke.");
                    echoYes.click();
                }
            } else {
                diag.note("Auch kein Mikrofon-Button gefunden.");
            }
        }
        page.waitForTimeout(3_000);
        diag.capture(page, "after-audio-choice");

        // Remote-Audio beobachten (verkuerzt auf 45s statt 90s Produktions-Timeout)
        String hasRemote = BrowserScripts.load(BrowserScripts.HAS_REMOTE_AUDIO);
        String audioStats = BrowserScripts.load(BrowserScripts.AUDIO_STATS);
        boolean remoteAudio = false;
        for (int i = 0; i < 15; i++) {
            Object ok = safeEval(hasRemote);
            Object tracks = safeEval(audioStats);
            diag.note("Audio-Poll " + i + ": hasRemoteAudio=" + ok + ", tracks=" + tracks);
            if (Boolean.TRUE.equals(ok)) {
                remoteAudio = true;
                break;
            }
            page.waitForTimeout(3_000);
        }
        diag.capture(page, "final");
        diag.note("Ergebnis: audioStageSeen=" + audioStageSeen + ", remoteAudio=" + remoteAudio);

        assertTrue(remoteAudio, "Kein Remote-Audio nach Audio-Auswahl - Details unter " + diag.dir());
    }

    // -------------------------------------------------- Produktions-Joiner

    @Test
    void productionJoinerFlow() {
        diag = new DiagnosticsCapture(Path.of("target", "it-diag", "production"));
        diag.attachConsole(page);
        BbbJoiner joiner = new BbbJoiner();
        try {
            joiner.join(page, roomUrl(), BOT_NAME, 60_000, 60_000);
            diag.capture(page, "joined");
            diag.note("Produktions-Joiner erfolgreich.");
        } catch (RuntimeException e) {
            diag.capture(page, "join-failed");
            diag.note("Produktions-Joiner fehlgeschlagen: " + e);
            throw e;
        }
    }

    /**
     * Reproduziert das Monitor-Tick-Verhalten nach dem Join: dismissModals darf
     * nach dem Schliessen aller Info-Fenster NICHTS mehr treffen (kein Log-Spam,
     * keine Klicks auf normale UI-Buttons). Dumpt, welche Close-Selektoren nach
     * dem Join noch sichtbare Elemente treffen.
     */
    @Test
    void dismissModalsIsIdempotentAfterJoin() {
        diag = new DiagnosticsCapture(Path.of("target", "it-diag", "dismiss-idempotence"));
        diag.attachConsole(page);
        BbbJoiner joiner = new BbbJoiner();
        joiner.join(page, roomUrl(), BOT_NAME, 60_000, 60_000);

        // Restliche Info-Fenster schliessen (wie dismissOverlays/erste Ticks)
        for (int i = 0; i < 5; i++) {
            if (!joiner.dismissModals(page)) break;
            page.waitForTimeout(700);
        }
        diag.capture(page, "after-initial-dismiss");

        List<String> closeSelectors = List.of(
                "[data-test=\"closeModal\"]",
                "[role=\"dialog\"] [data-test=\"closeModal\"]",
                "[role=\"dialog\"] button:has(i.icon-bbb-close)",
                "button:has(i.icon-bbb-close)",
                "[role=\"dialog\"] button[aria-label*=\"close\" i]");

        // 5 Monitor-Ticks simulieren und protokollieren, was noch matcht
        int clickedTicks = 0;
        for (int tick = 0; tick < 5; tick++) {
            for (String sel : closeSelectors) {
                Object matches = page.evaluate("""
                        (sel) => Array.from(document.querySelectorAll(sel))
                            .filter(el => el.getBoundingClientRect().width > 0)
                            .map(el => ({
                                aria: el.getAttribute('aria-label') || '',
                                cls: (el.getAttribute('class') || '').slice(0, 80),
                                dialog: (() => {
                                    const d = el.closest('[role="dialog"], [class*="modal" i]');
                                    return d ? ((d.getAttribute('aria-label') || d.getAttribute('data-test')
                                        || (d.getAttribute('class') || '').slice(0, 60))
                                        + ' ariaModal=' + d.getAttribute('aria-modal')) : 'KEIN-DIALOG';
                                })()
                            }))""", sel);
                String s = String.valueOf(matches);
                if (!"[]".equals(s)) {
                    diag.note("Tick " + tick + " Selektor '" + sel + "' trifft noch: " + s);
                }
            }
            if (joiner.dismissModals(page)) {
                clickedTicks++;
                diag.note("Tick " + tick + ": dismissModals hat geklickt!");
            }
            page.waitForTimeout(1_500);
        }
        diag.capture(page, "after-ticks");
        assertTrue(clickedTicks == 0,
                "dismissModals klickt nach dem Schliessen weiter (" + clickedTicks + "/5 Ticks) - Log-Spam/Fehlklicks");
    }

    // ------------------------------------------------------------- Helfer

    private Locator firstVisible(List<String> selectors) {
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    diag.note("Selektor trifft: " + sel);
                    return loc;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    /** Prueft bekannte Audio-/Meeting-Selektoren und listet die sichtbaren auf. */
    private String matchedKnownSelectors() {
        List<String> known = List.of(
                "button[data-test=\"listenOnlyBtn\"]",
                "button[data-test=\"microphoneBtn\"]",
                "button[data-test=\"joinEchoTestButton\"]",
                "button[data-test=\"echoYesBtn\"]",
                "[data-test=\"audioModal\"]",
                "[data-test=\"audioModalHeader\"]",
                "[data-test=\"userListItem\"]",
                "button[data-test=\"joinBtn\"]");
        StringBuilder sb = new StringBuilder();
        for (String sel : known) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(sel);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return sb.toString();
    }

    private Object safeEval(String script) {
        try {
            return page.evaluate(script);
        } catch (RuntimeException e) {
            return "eval-error: " + e.getMessage();
        }
    }
}
