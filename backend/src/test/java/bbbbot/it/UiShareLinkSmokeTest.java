package bbbbot.it;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI-Smoke-Test fuer Auswertungs-Vorlage beim Upload und oeffentlichen
 * Freigabe-Link: Upload mit gewaehlter Vorlage, Link in der Detailansicht
 * erzeugen und die Freigabe-Ansicht in einem frischen Browser-Kontext
 * (also ohne jede Anmeldung) oeffnen.
 *
 * Erwartet ein laufendes Backend samt ausgeliefertem Frontend, z.B.:
 *   mvn test -Dtest=UiShareLinkSmokeTest \
 *     -Dui.it.url=http://localhost:8099 -Dui.it.user=admin -Dui.it.password=... \
 *     -Dui.it.file=/pfad/zu/test-audio.mp3
 */
@EnabledIfSystemProperty(named = "ui.it.url", matches = ".+")
class UiShareLinkSmokeTest {

    @Test
    void vorlageBeimUploadUndFreigabeLink() {
        String baseUrl = System.getProperty("ui.it.url");
        String user = System.getProperty("ui.it.user", "admin");
        String password = System.getProperty("ui.it.password", "admin");
        Path file = Path.of(System.getProperty("ui.it.file"));
        String title = "UI-Smoke-Freigabe " + System.currentTimeMillis();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            DiagnosticsCapture diag = new DiagnosticsCapture(Path.of("target", "it-diag", "ui-share"));
            // Deutsch erzwingen: Die Oberflaeche folgt sonst der Browsersprache,
            // und dieser Test sucht Knoepfe an ihrer deutschen Beschriftung.
            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions().setLocale("de-DE"));
            Page page = context.newPage();
            diag.attachConsole(page);

            page.navigate(baseUrl + "/login");
            page.fill("#login-username", user);
            page.fill("#login-password", password);
            page.click("button[type=submit]");
            page.waitForURL(url -> !url.contains("/login"), new Page.WaitForURLOptions().setTimeout(15_000));

            // Upload mit anderer Auswertungs-Vorlage als "Meeting (Standard)"
            page.navigate(baseUrl + "/recordings");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Aufnahme hochladen")).click();
            page.setInputFiles("#upload-file", file);
            page.fill("#upload-title", title);
            page.selectOption("#upload-preset", "talk");
            diag.capture(page, "upload-dialog");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Hochladen").setExact(true)).click();
            page.waitForSelector("text=Datei hochgeladen",
                    new Page.WaitForSelectorOptions().setTimeout(30_000));

            // Detailansicht der neuen Aufnahme
            Locator row = page.locator("tr", new Page.LocatorOptions().setHasText(title));
            row.first().waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            row.first().locator("a").first().click();
            page.waitForURL(url -> url.contains("/recordings/"),
                    new Page.WaitForURLOptions().setTimeout(15_000));

            // Die gewaehlte Vorlage steckt an der Aufnahme: "Auswertung anpassen"
            // ist als angepasst markiert.
            Locator options = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Auswertung anpassen"));
            options.first().waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            assertTrue(options.first().locator(".tag").isVisible(),
                    "Die beim Upload gewaehlte Vorlage wurde nicht an der Aufnahme gespeichert");

            // Freigabe-Link erzeugen
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Teilen").setExact(true)).click();
            page.waitForSelector("#share-link-expiry",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
            page.selectOption("#share-link-expiry", "30");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Link erzeugen")).click();
            Locator linkCode = page.locator(".share-link-item .token-reveal code");
            linkCode.first().waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            String shareUrl = linkCode.first().textContent();
            diag.capture(page, "share-dialog");
            assertNotNull(shareUrl);
            assertTrue(shareUrl.contains("/share/"), "Unerwartete Freigabe-Adresse: " + shareUrl);

            // Frischer Kontext = kein Login-Token, keine Sitzung: genau die Lage
            // eines Empfaengers, der den Link bekommt.
            BrowserContext anonymous = browser.newContext(
                    new Browser.NewContextOptions().setLocale("de-DE"));
            Page shared = anonymous.newPage();
            diag.attachConsole(shared);
            shared.navigate(shareUrl);
            shared.waitForSelector("text=" + title, new Page.WaitForSelectorOptions().setTimeout(15_000));
            diag.capture(shared, "public-view");
            assertTrue(shared.locator("text=Freigegeben von").isVisible(),
                    "Freigabe-Ansicht zeigt den Freigebenden nicht");
            assertTrue(shared.locator("audio").first().isVisible(),
                    "Freigabe-Ansicht bietet kein Audio an");
            // Die Ansicht laeuft ohne Anmeldung - keine Umleitung auf /login
            assertTrue(shared.url().contains("/share/"),
                    "Freigabe-Ansicht wurde umgeleitet: " + shared.url());
            diag.flush();
        }
    }
}
