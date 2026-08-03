package bbbbot.bot;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * UI-basierter BBB-Join (Portierung von src/providers/joinDirect.ts).
 *
 * Der Bot nutzt bewusst KEINE BBB-API und keine Checksums: Er navigiert wie
 * ein menschlicher Nutzer auf die fertige, bereits autorisierte Raum-URL
 * (im Intranet z.B. eine Nextcloud-BBB-Integrations-URL), traegt den Namen
 * ins Formular ein und klickt sich durch. "Nur zuhoeren" wird bevorzugt.
 */
public class BbbJoiner {

    private static final Logger log = LoggerFactory.getLogger(BbbJoiner.class);

    private static final List<String> NAME_SELECTORS = List.of(
            "#displayname",
            "input[name=\"displayname\"]",
            "input[placeholder*=\"Anzeigename\" i]",
            "input[placeholder*=\"Name\" i]",
            "input[type=\"text\"][required]",
            "input[type=\"text\"]"
    );

    private static final List<String> SUBMIT_SELECTORS = List.of(
            "#displayname-submit",
            "input[type=\"submit\"]#displayname-submit",
            "input[type=\"submit\"][id*=\"displayname\"]",
            "button[type=\"submit\"]",
            "input[type=\"submit\"]"
    );

    private static final List<String> LISTEN_ONLY_SELECTORS = List.of(
            "button[data-test=\"listenOnlyBtn\"]",
            "button[aria-label*=\"Nur zuhören\" i]",
            "button:has-text(\"Nur zuhören\")",
            "button:has-text(\"Listen only\")"
    );

    // Mikrofon-Fallback, falls der Raum kein "Nur zuhoeren" anbietet
    // (listenOnlyMode kann serverseitig deaktiviert sein).
    private static final List<String> MICROPHONE_SELECTORS = List.of(
            "button[data-test=\"microphoneBtn\"]",
            "button[aria-label*=\"Mikrofon\" i]",
            "button[aria-label*=\"Microphone\" i]"
    );

    // Bestaetigung des Echo-Tests nach dem Mikrofon-Join (auf das Audio-Modal
    // begrenzt, damit Text-Fallbacks nicht irgendeinen anderen Button treffen).
    private static final List<String> ECHO_CONFIRM_SELECTORS = List.of(
            "button[data-test=\"echoYesBtn\"]",
            "[data-test=\"audioModal\"] button:has-text(\"Yes\")",
            "[data-test=\"audioModal\"] button:has-text(\"Ja\")"
    );

    // Toolbar-Button, der das Audio-Auswahl-Modal (wieder) oeffnet, falls es
    // geschlossen wurde, bevor eine Auswahl getroffen war.
    private static final List<String> JOIN_AUDIO_TOOLBAR_SELECTORS = List.of(
            "button[data-test=\"joinAudio\"]",
            "button[aria-label*=\"Join audio\" i]",
            "button[aria-label*=\"Audio starten\" i]"
    );

    // Kombinierter Wartetreffer: Audio-Auswahl oder fertige Meeting-UI.
    private static final String AUDIO_PROMPT_OR_MEETING_UI =
            "[data-test=\"audioModal\"], button[data-test=\"listenOnlyBtn\"], "
            + "button[data-test=\"microphoneBtn\"], button[data-test=\"joinBtn\"], "
            + "[data-test=\"userListItem\"]";

    private static final List<String> JOIN_BTN_SELECTORS = List.of(
            "button[data-test=\"joinBtn\"]",
            "button[data-test^=\"join\"]",
            "button:has-text(\"Join\")",
            "button:has-text(\"Beitreten\")",
            "button:has-text(\"Jetzt teilnehmen\")",
            "button[aria-label*=\"Beitreten\" i]",
            "button[aria-label*=\"Join\" i]"
    );

    // Schliessen-Schaltflaechen von Info-/Modal-Fenstern, die nach dem Join die
    // geteilte Ansicht verdecken koennen (z.B. "Session details"/"Sitzungsdetails").
    // Sprachunabhaengig ueber data-test/Icon-Klasse (Bot-Oberflaeche ist Englisch).
    private static final List<String> DIALOG_CLOSE_SELECTORS = List.of(
            "[data-test=\"closeModal\"]",
            "[role=\"dialog\"] [data-test=\"closeModal\"]",
            "[role=\"dialog\"] button:has(i.icon-bbb-close)",
            "button:has(i.icon-bbb-close)",
            "[role=\"dialog\"] button[aria-label*=\"close\" i]"
    );

    public void join(Page page, String meetingUrl, String displayName, long joinTimeoutMs, long audioReadyTimeoutMs) {
        log.info("Navigiere zur Meeting-URL: {}", meetingUrl);
        page.navigate(meetingUrl, new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(joinTimeoutMs));

        fillAndSubmitName(page, displayName);

        // Nach dem Namens-Submit leitet z.B. Nextcloud erst nach einigen Sekunden
        // auf den BBB-html5client weiter; die Audio-Auswahl erscheint entsprechend
        // spaet. Auf sie (oder die fertige Meeting-UI) warten statt fix 800 ms.
        try {
            page.waitForSelector(AUDIO_PROMPT_OR_MEETING_UI,
                    new Page.WaitForSelectorOptions().setTimeout(45_000));
        } catch (RuntimeException e) {
            log.warn("Weder Audio-Auswahl noch Meeting-UI innerhalb 45s sichtbar, fahre fort.");
        }

        if (!chooseAudio(page)) {
            log.info("Keine Audio-Auswahl geklickt (evtl. Auto-Join).");
        }

        // Auf Meeting-UI warten (Teilnehmerliste)
        try {
            page.waitForSelector("[data-test=\"userListItem\"], [data-test^=\"userListItem\"], [class*=\"userList\"]",
                    new Page.WaitForSelectorOptions().setTimeout(12_000));
        } catch (RuntimeException e) {
            log.debug("Teilnehmerliste nicht innerhalb 12s sichtbar, fahre fort.");
        }

        log.info("Warte auf Remote-Audio (Timeout {} ms)", audioReadyTimeoutMs);
        waitForRemoteAudioResilient(page, audioReadyTimeoutMs);

        // Info-/Modal-Fenster (z.B. "Sitzungsdetails") wegklicken, damit sie im
        // aufgenommenen Video die geteilte Ansicht nicht verdecken. Erst NACH dem
        // Audio-Join, damit die Audio-Auswahl nicht versehentlich geschlossen wird.
        dismissOverlays(page);

        log.info("Join abgeschlossen, Remote-Audio liegt an.");
    }

    /**
     * Trifft die Audio-Auswahl: "Nur zuhoeren" bevorzugt, sonst Mikrofon mit
     * Echo-Test-Bestaetigung, sonst regulaerer Join-Button (aeltere Themes).
     */
    private boolean chooseAudio(Page page) {
        if (tryClickSelectors(page, LISTEN_ONLY_SELECTORS, "listen-only")) {
            return true;
        }
        if (tryClickSelectors(page, MICROPHONE_SELECTORS, "microphone")) {
            confirmEchoTest(page);
            return true;
        }
        // Join-Button-Fallback nur ausserhalb der Meeting-UI (aeltere Join-Seiten):
        // innerhalb der Meeting-UI wuerde data-test^="join" die Toolbar-Buttons
        // joinAudio/joinVideo treffen. Erscheint die Audio-Auswahl erst spaeter,
        // uebernimmt waitForRemoteAudioResilient sie.
        if (isMeetingUiVisible(page)) {
            return false;
        }
        return tryClickSelectors(page, JOIN_BTN_SELECTORS, "join");
    }

    private boolean isMeetingUiVisible(Page page) {
        try {
            Locator meetingUi = page.locator(
                    "[data-test=\"userListItem\"], button[data-test=\"joinAudio\"]").first();
            return meetingUi.count() > 0 && meetingUi.isVisible();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Bestaetigt nach dem Mikrofon-Join den Echo-Test, sofern einer erscheint
     * (je nach Server-Konfiguration entfaellt er). Best-effort.
     */
    private void confirmEchoTest(Page page) {
        for (int attempt = 0; attempt < 15; attempt++) {
            if (tryClickSelectors(page, ECHO_CONFIRM_SELECTORS, "echo-confirm")) {
                return;
            }
            // Modal bereits weg -> Audio-Join lief ohne Echo-Test durch.
            try {
                Locator modal = page.locator("[data-test=\"audioModal\"]").first();
                if (modal.count() == 0 || !modal.isVisible()) return;
            } catch (RuntimeException ignored) {
            }
            page.waitForTimeout(1000);
        }
        log.warn("Echo-Test-Bestaetigung nicht gefunden - fahre fort.");
    }

    private void fillAndSubmitName(Page page, String displayName) {
        Locator nameLocator = null;
        for (String sel : NAME_SELECTORS) {
            try {
                Locator locator = page.locator(sel).first();
                if (locator.count() > 0) {
                    nameLocator = locator;
                    log.info("Namensfeld gefunden: {}", sel);
                    break;
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (nameLocator == null) {
            log.warn("Kein Namensfeld gefunden - evtl. bereits im Raum oder abweichendes Theme.");
            return;
        }
        try {
            String current = nameLocator.inputValue().trim();
            if (!current.isEmpty()) {
                log.info("Namensfeld bereits gefuellt, ueberspringe.");
                return;
            }
            try {
                nameLocator.click(new Locator.ClickOptions().setTimeout(3000));
            } catch (RuntimeException ignored) {
            }
            nameLocator.fill(displayName, new Locator.FillOptions().setTimeout(5000));

            boolean submitted = false;
            for (String sel : SUBMIT_SELECTORS) {
                try {
                    Locator submitEl = page.locator(sel).first();
                    if (submitEl.count() > 0 && submitEl.isVisible()) {
                        log.info("Klicke Namens-Submit: {}", sel);
                        submitEl.click(new Locator.ClickOptions().setTimeout(3000));
                        submitted = true;
                        break;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            if (!submitted) {
                log.info("Kein Submit-Element gefunden - Enter als Fallback.");
                nameLocator.press("Enter");
            }
        } catch (RuntimeException e) {
            log.warn("Namensfeld ausfuellen/absenden fehlgeschlagen: {}", e.getMessage());
        }
    }

    /**
     * Schliesst direkt nach dem Join etwaige Info-/Modal-Fenster. Mehrere Versuche,
     * da solche Fenster leicht verzoegert erscheinen; das dauerhafte Schliessen
     * uebernimmt danach der Monitor-Tick ({@link #dismissModals}).
     */
    private void dismissOverlays(Page page) {
        for (int attempt = 0; attempt < 4; attempt++) {
            if (dismissModals(page)) return;
            page.waitForTimeout(700);
        }
    }

    /**
     * Schliesst ein aktuell sichtbares Modal-Fenster (z.B. "Session details"), das
     * die geteilte Ansicht verdeckt. Klickt gezielt dessen Schliessen-Control
     * ({@code data-test="closeModal"} / {@code i.icon-bbb-close}). Gibt true zurueck,
     * wenn ein Fenster geschlossen wurde. Best-effort, Fehlschlaege sind unkritisch.
     */
    public boolean dismissModals(Page page) {
        for (String sel : DIALOG_CLOSE_SELECTORS) {
            try {
                Locator all = page.locator(sel);
                int count = all.count();
                for (int i = 0; i < count; i++) {
                    Locator el = all.nth(i);
                    try {
                        if (!el.isVisible()) continue;
                        // Nur Schliessen-Controls in echten Modal-Containern klicken.
                        // Sonst treffen die Icon-/Label-Selektoren dauerhaft sichtbare
                        // UI-Buttons (z.B. "Cancel (Esc)" am Chat-Eingabefeld) und der
                        // Monitor-Tick klickt/loggt alle paar Sekunden ins Leere.
                        // Das Audio-Auswahl-Modal NIE schliessen: sonst wird die
                        // Audio-Auswahl uebersprungen und der Bot bekommt kein Audio.
                        Object verdict = el.evaluate("""
                                el => {
                                  if (el.closest('[data-test="audioModal"]')) return 'audio';
                                  const container = el.closest(
                                    '[role="dialog"], [aria-modal="true"], [data-test$="Modal"], [class*="modal" i]');
                                  return container ? 'ok' : 'none';
                                }""");
                        if (!"ok".equals(verdict)) continue;
                        String label = "";
                        try {
                            String aria = el.getAttribute("aria-label");
                            if (aria != null) label = aria;
                        } catch (RuntimeException ignored) {
                        }
                        el.click(new Locator.ClickOptions().setTimeout(2000));
                        // Erst NACH erfolgreichem Klick loggen - ein dauerhaft
                        // fehlschlagender Klick darf das Log nicht fluten.
                        log.info("Modal-Fenster geschlossen ('{}' via {}).", label, sel);
                        return true;
                    } catch (RuntimeException ignored) {
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    private boolean tryClickSelectors(Page page, List<String> selectors, String desc) {
        for (String sel : selectors) {
            try {
                Locator el = page.locator(sel).first();
                if (el.count() > 0 && el.isVisible()) {
                    log.info("Klicke {}-Button: {}", desc, sel);
                    try {
                        el.click(new Locator.ClickOptions().setTimeout(5000));
                    } catch (RuntimeException e) {
                        log.warn("Klick {} fehlgeschlagen fuer {}: {}", desc, sel, e.getMessage());
                    }
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    private void waitForRemoteAudioResilient(Page page, long timeoutMs) {
        long start = System.currentTimeMillis();
        String script = BrowserScripts.load(BrowserScripts.HAS_REMOTE_AUDIO);
        int iteration = 0;
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                Object ok = page.evaluate(script);
                if (Boolean.TRUE.equals(ok)) return;
            } catch (RuntimeException ignored) {
            }
            // Falls die Audio-Auswahl verspaetet erschien oder ein Klick blockiert
            // wurde: Auswahl erneut treffen, solange das Modal sichtbar ist.
            try {
                Locator modal = page.locator("[data-test=\"audioModal\"]").first();
                if (modal.count() > 0 && modal.isVisible()) {
                    log.info("Audio-Auswahl (erneut) waehrend Audio-Wartezeit.");
                    chooseAudio(page);
                } else if (iteration > 0 && iteration % 8 == 0) {
                    // Kein Audio und kein Modal: Auswahl wurde vermutlich geschlossen,
                    // ohne dass Audio verbunden ist -> ueber den Toolbar-Button neu oeffnen.
                    if (tryClickSelectors(page, JOIN_AUDIO_TOOLBAR_SELECTORS, "join-audio-reopen")) {
                        log.info("Audio-Auswahl ueber Toolbar neu geoeffnet.");
                    }
                }
            } catch (RuntimeException ignored) {
            }
            iteration++;
            page.waitForTimeout(1000);
        }
        throw new IllegalStateException("Timeout: keine Remote-Audio-Elemente gefunden.");
    }
}
