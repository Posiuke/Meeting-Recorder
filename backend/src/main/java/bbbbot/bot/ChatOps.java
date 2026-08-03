package bbbbot.bot;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chat-Operationen auf der BBB-Seite (Portierung von src/chat/extraction.ts
 * und messaging.ts): Chat oeffnen, Nachrichten senden und extrahieren.
 * Alle Methoden muessen auf dem Playwright-Thread der Bot-Instanz laufen.
 */
public class ChatOps {

    private static final Logger log = LoggerFactory.getLogger(ChatOps.class);

    private final Page page;

    public ChatOps(Page page) {
        this.page = page;
    }

    public void ensureChatOpen() {
        if (findChatInput() != null) return;
        List<Locator> openers = new ArrayList<>();
        Pattern chatPattern = Pattern.compile("öffentlicher chat|public chat|chat", Pattern.CASE_INSENSITIVE);
        openers.add(page.getByRole(com.microsoft.playwright.options.AriaRole.TAB,
                new Page.GetByRoleOptions().setName(chatPattern)));
        openers.add(page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(chatPattern)));
        openers.add(page.locator("button:has-text(\"Öffentlicher Chat\")"));
        for (Locator opener : openers) {
            try {
                Locator el = opener.first();
                if (el.isVisible()) {
                    String selected = el.getAttribute("aria-selected");
                    if (!"true".equals(selected)) {
                        el.click(new Locator.ClickOptions().setTimeout(1500));
                    }
                    page.waitForTimeout(300);
                    return;
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    public Locator findChatInput() {
        List<Locator> candidates = new ArrayList<>();
        candidates.add(page.getByRole(com.microsoft.playwright.options.AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName(Pattern.compile("message|nachricht|chat", Pattern.CASE_INSENSITIVE))));
        candidates.add(page.getByRole(com.microsoft.playwright.options.AriaRole.TEXTBOX));
        candidates.add(page.locator("textarea"));
        candidates.add(page.locator("div[contenteditable=\"true\"][role=\"textbox\"]"));
        candidates.add(page.locator("div[contenteditable=\"true\"]"));
        for (Locator candidate : candidates) {
            try {
                Locator el = candidate.first();
                if (el.isVisible()) return el;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    public void sendMessage(String message) {
        ensureChatOpen();
        Locator input = findChatInput();
        if (input == null) throw new IllegalStateException("Chat-Eingabefeld nicht gefunden");
        try {
            input.click(new Locator.ClickOptions().setTimeout(1500));
        } catch (RuntimeException ignored) {
        }
        try {
            input.fill(message, new Locator.FillOptions().setTimeout(2000));
        } catch (RuntimeException e) {
            input.type(message, new Locator.TypeOptions().setDelay(10));
        }
        input.press("Enter");
    }

    /** Gesamter Chat als eine Zeile pro Nachricht ("User: Body") fuer die Befehls-Erkennung. */
    public String getAllChatText() {
        try {
            Object result = page.evaluate(BrowserScripts.load(BrowserScripts.CHAT_TEXT));
            return result == null ? "" : result.toString();
        } catch (RuntimeException e) {
            log.debug("getAllChatText fehlgeschlagen: {}", e.getMessage());
            return "";
        }
    }

    /** Alle Chat-Nachrichten im Format "[Zeit] User:\nBody", Keepalive-Nachrichten gefiltert. */
    @SuppressWarnings("unchecked")
    public List<String> extractMessages(String keepalivePrefix) {
        ensureChatOpen();
        try {
            Object result = page.evaluate(BrowserScripts.load(BrowserScripts.CHAT_MESSAGES));
            List<String> raw = result instanceof List ? (List<String>) result : List.of();
            List<String> filtered = new ArrayList<>();
            for (String m : raw) {
                String trimmed = m == null ? "" : m.trim();
                if (trimmed.isEmpty()) continue;
                if (keepalivePrefix != null && !keepalivePrefix.isEmpty() && trimmed.startsWith(keepalivePrefix)) continue;
                filtered.add(trimmed);
            }
            return filtered;
        } catch (RuntimeException e) {
            log.warn("extractMessages fehlgeschlagen: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Chat-Text nach dem Session-Marker (Datenschutz-Filter fuer die KI-Auswertung).
     * Ohne Marker oder wenn er nicht gefunden wird: leerer String, KEIN Fallback
     * auf den Gesamt-Chat.
     */
    public String getChatSinceMarker(String marker, int retries, long retryDelayMs, String keepalivePrefix) {
        if (marker == null || marker.isEmpty()) {
            log.warn("Kein aktiver Marker - Chat wird aus Datenschutzgruenden leer uebernommen");
            return "";
        }
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                String fullChat = String.join("\n", extractMessages(keepalivePrefix));
                int idx = fullChat.indexOf(marker);
                if (idx >= 0) {
                    return fullChat.substring(idx + marker.length()).trim();
                }
                log.warn("Marker nicht im Chat gefunden (Versuch {}/{})", attempt, retries);
            } catch (RuntimeException e) {
                log.warn("getChatSinceMarker Versuch {}/{} fehlgeschlagen: {}", attempt, retries, e.getMessage());
            }
            if (attempt < retries) {
                page.waitForTimeout(retryDelayMs);
            }
        }
        return "";
    }

    /** START-Befehl im Gesamt-Chat suchen (neueste Nachricht zuerst), inkl. Metadaten fuer Debounce. */
    public record StartCommandInfo(boolean found, String messagePreview, String timestamp) {}

    public StartCommandInfo detectStartCommandWithInfo(String command) {
        try {
            // JS-Regex kennt kein \Q...\E (Pattern.quote), daher manuell escapen:
            String escaped = command.replaceAll("[.*+?^${}()|\\[\\]\\\\]", "\\\\$0");
            Object result = page.evaluate(BrowserScripts.load(BrowserScripts.START_COMMAND_INFO), escaped);
            if (result instanceof java.util.Map<?, ?> map && Boolean.TRUE.equals(map.get("found"))) {
                Object preview = map.get("messagePreview");
                Object timestamp = map.get("timestamp");
                return new StartCommandInfo(true,
                        preview == null ? "" : preview.toString(),
                        timestamp == null ? "" : timestamp.toString());
            }
        } catch (RuntimeException e) {
            log.debug("detectStartCommandWithInfo fehlgeschlagen: {}", e.getMessage());
        }
        return new StartCommandInfo(false, null, null);
    }
}
