package bbbbot.it;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI-Smoke-Test fuer den Aufnahme-Upload: Login, Upload-Dialog oeffnen,
 * Datei hochladen und pruefen, dass die Aufnahme mit Upload-Badge erscheint.
 *
 * Erwartet ein laufendes Backend + Frontend (Dev-Server), z.B.:
 *   mvn test -Dtest=UiUploadSmokeTest \
 *     -Dui.it.url=http://localhost:5199 -Dui.it.user=admin -Dui.it.password=... \
 *     -Dui.it.file=/pfad/zu/test-audio.mp3
 */
@EnabledIfSystemProperty(named = "ui.it.url", matches = ".+")
class UiUploadSmokeTest {

    @Test
    void uploadViaUi() {
        String baseUrl = System.getProperty("ui.it.url");
        String user = System.getProperty("ui.it.user", "admin");
        String password = System.getProperty("ui.it.password", "admin");
        Path file = Path.of(System.getProperty("ui.it.file"));
        String title = "UI-Smoke-Upload " + System.currentTimeMillis();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            DiagnosticsCapture diag = new DiagnosticsCapture(Path.of("target", "it-diag", "ui-upload"));
            diag.attachConsole(page);

            page.navigate(baseUrl + "/login");
            page.fill("#login-username", user);
            page.fill("#login-password", password);
            page.click("button[type=submit]");
            page.waitForURL(url -> !url.contains("/login"), new Page.WaitForURLOptions().setTimeout(15_000));
            diag.capture(page, "logged-in");

            page.navigate(baseUrl + "/recordings");
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Aufnahme hochladen")).click();
            diag.capture(page, "dialog-open");

            page.setInputFiles("#upload-file", file);
            page.fill("#upload-title", title);
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Hochladen").setExact(true)).click();

            // Erfolgsmeldung + Aufnahme mit Titel und Upload-Badge in der Liste
            page.waitForSelector("text=Datei hochgeladen",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));
            diag.capture(page, "uploaded");
            // String-Variante (Substring-Match) - Pattern.quote(\Q...\E) kennt die
            // JS-Regex-Engine von Playwright nicht.
            Locator row = page.locator("tr", new Page.LocatorOptions().setHasText(title));
            row.first().waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            assertTrue(row.first().locator(".badge", new Locator.LocatorOptions()
                            .setHasText("Upload")).isVisible(),
                    "Upload-Badge fehlt in der Zeile der hochgeladenen Aufnahme");
            diag.capture(page, "row-visible");
            diag.flush();
        }
    }
}
