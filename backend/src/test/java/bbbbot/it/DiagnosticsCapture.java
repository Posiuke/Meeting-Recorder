package bbbbot.it;

import com.microsoft.playwright.Page;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnose-Helfer fuer Live-Integrationstests: haelt pro Schritt einen
 * Screenshot und ein DOM-Inventar (Buttons, Inputs, Dialoge, Audio-Elemente)
 * fest, damit Abweichungen der BBB-Oberflaeche (z.B. neue data-test-Attribute)
 * ohne manuelles Zuschauen analysiert werden koennen.
 */
class DiagnosticsCapture {

    private final Path outDir;
    private final List<String> consoleLog = new ArrayList<>();
    private int step;

    DiagnosticsCapture(Path outDir) {
        this.outDir = outDir;
        try {
            Files.createDirectories(outDir);
        } catch (Exception e) {
            throw new IllegalStateException("Diagnose-Verzeichnis nicht anlegbar: " + outDir, e);
        }
    }

    /** Konsole/Fehler der Seite mitschreiben (WebRTC-/getUserMedia-Fehler landen dort). */
    void attachConsole(Page page) {
        page.onConsoleMessage(msg -> consoleLog.add("[console:" + msg.type() + "] " + msg.text()));
        page.onPageError(err -> consoleLog.add("[pageerror] " + err));
    }

    void capture(Page page, String label) {
        String prefix = "%02d_%s".formatted(step++, label);
        try {
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(outDir.resolve(prefix + ".png")).setFullPage(false));
        } catch (RuntimeException e) {
            note(prefix + " Screenshot fehlgeschlagen: " + e.getMessage());
        }
        try {
            Object json = page.evaluate(DOM_INVENTORY);
            Files.writeString(outDir.resolve(prefix + ".json"), String.valueOf(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            note(prefix + " DOM-Inventar fehlgeschlagen: " + e.getMessage());
        }
    }

    void note(String message) {
        consoleLog.add("[note] " + message);
        System.out.println("[IT] " + message);
    }

    void flush() {
        try {
            Files.writeString(outDir.resolve("console.log"), String.join("\n", consoleLog), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    Path dir() {
        return outDir;
    }

    private static final String DOM_INVENTORY = """
            () => {
              const attr = (el, n) => (el.getAttribute && el.getAttribute(n)) || '';
              const vis = el => { try { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch { return false; } };
              const txt = (el, max) => ((el.innerText || el.value || '') + '').replace(/\\s+/g, ' ').trim().slice(0, max);
              const inventory = {
                url: location.href,
                title: document.title,
                buttons: Array.from(document.querySelectorAll('button, input[type="submit"], [role="button"]')).map(b => ({
                  tag: b.tagName, dataTest: attr(b, 'data-test'), ariaLabel: attr(b, 'aria-label'), id: b.id,
                  cls: attr(b, 'class').slice(0, 120), text: txt(b, 80), visible: vis(b), disabled: !!b.disabled
                })),
                inputs: Array.from(document.querySelectorAll('input')).map(i => ({
                  id: i.id, name: i.name, type: i.type, placeholder: i.placeholder,
                  dataTest: attr(i, 'data-test'), visible: vis(i)
                })),
                dialogs: Array.from(document.querySelectorAll('[role="dialog"], [class*="modal" i], [data-test*="Modal"]')).map(d => ({
                  dataTest: attr(d, 'data-test'), ariaLabel: attr(d, 'aria-label'),
                  cls: attr(d, 'class').slice(0, 160), text: txt(d, 600), visible: vis(d)
                })),
                audios: Array.from(document.querySelectorAll('audio')).map(a => ({
                  id: a.id, cls: attr(a, 'class'), src: a.currentSrc, readyState: a.readyState, paused: a.paused,
                  tracks: (() => { try { const s = a.srcObject; return s && s.getAudioTracks ? s.getAudioTracks().length : 0; } catch { return -1; } })()
                })),
              };
              return JSON.stringify(inventory, null, 2);
            }
            """;
}
