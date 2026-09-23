package bbbbot.bot;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chat-Operationen auf der BBB-Seite (Portierung von src/chat/extraction.ts
 * und messaging.ts): Chat oeffnen, Nachrichten senden und extrahieren.
 * Alle Methoden muessen auf dem Playwright-Thread der Bot-Instanz laufen.
 */
public class ChatOps {

    private static final Logger log = LoggerFactory.getLogger(ChatOps.class);

    /** Hoechstzahl gemerkter eigener Texte - der Bot schreibt nur wenige Hinweise. */
    private static final int MAX_SENT_TEXTS = 200;

    private final Page page;

    /**
     * Texte, die der Bot in dieser Sitzung selbst gesendet hat (normalisiert).
     * Rueckfall fuer die Erkennung eigener Nachrichten, falls BBB den
     * Bearbeiten-Button nicht anzeigt.
     */
    private final Set<String> sentTexts;

    public ChatOps(Page page) {
        this(page, java.util.Collections.synchronizedSet(new LinkedHashSet<>()));
    }

    /**
     * @param sentTexts gemeinsamer Speicher der eigenen Texte - die Bot-Instanz
     *                  reicht ihn ueber einen Reconnect weiter. Nach dem
     *                  Wiederbeitritt ist der Bot fuer BBB ein neuer Nutzer, seine
     *                  alten Nachrichten tragen dann keinen Bearbeiten-Button mehr.
     */
    public ChatOps(Page page, Set<String> sentTexts) {
        this.page = page;
        this.sentTexts = sentTexts;
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
        rememberSent(message);
        input.press("Enter");
    }

    private void rememberSent(String message) {
        synchronized (sentTexts) {
            if (sentTexts.size() >= MAX_SENT_TEXTS) {
                sentTexts.remove(sentTexts.iterator().next());
            }
            sentTexts.add(CommandDetector.normalize(message));
        }
    }

    /**
     * Eine Chat-Nachricht.
     *
     * @param own  vom Bot selbst gesendet
     * @param time Uhrzeit, wie BBB sie anzeigt (nur an der letzten Nachricht
     *             einer Gruppe, sonst leer)
     * @param text Nachrichtentext, Zeilenumbrueche erhalten
     */
    public record ChatEntry(boolean own, String time, String text) {}

    /** Alle Nachrichten in Chat-Reihenfolge, eigene markiert (siehe chatMessages.js). */
    @SuppressWarnings("unchecked")
    public List<ChatEntry> readEntries() {
        try {
            Object result = page.evaluate(BrowserScripts.load(BrowserScripts.CHAT_MESSAGES),
                    List.copyOf(sentTexts));
            if (!(result instanceof List<?> raw)) return List.of();
            List<ChatEntry> entries = new ArrayList<>();
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object text = m.get("text");
                if (text == null || text.toString().isBlank()) continue;
                Object time = m.get("time");
                entries.add(new ChatEntry(Boolean.TRUE.equals(m.get("own")),
                        time == null ? "" : time.toString(), text.toString()));
            }
            return entries;
        } catch (RuntimeException e) {
            log.debug("Chat konnte nicht gelesen werden: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Chat als eine Zeile pro Nachricht fuer die Befehlserkennung. Eigene
     * Nachrichten des Bots fehlen - mit einer Ausnahme: Zeilen mit Session-Marker
     * bleiben als Anker stehen, denn die Erkennung sucht Befehle erst NACH dem
     * Marker. {@link CommandDetector#stripBotMarkerLines} entfernt sie danach.
     */
    public String getAllChatText() {
        StringBuilder sb = new StringBuilder();
        for (ChatEntry e : readEntries()) {
            String line = CommandDetector.normalize(e.text());
            if (e.own() && !SessionMarkers.containsMarker(line)) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Nachrichten der Teilnehmer im Format "[Zeit]\nText" - ohne Nachrichten des Bots. */
    public List<String> extractMessages() {
        ensureChatOpen();
        return readEntries().stream()
                .filter(e -> !e.own())
                .map(ChatOps::format)
                .toList();
    }

    private static String format(ChatEntry e) {
        return e.time().isEmpty() ? e.text() : "[" + e.time() + "]\n" + e.text();
    }

    /**
     * Chat seit dem Session-Marker (Datenschutz-Filter fuer die KI-Auswertung und
     * den Reiter "Chat" der Aufnahme) - nur Nachrichten der Teilnehmer, nichts
     * vom Bot. Ohne Marker oder wenn er nicht gefunden wird: leerer String, KEIN
     * Fallback auf den Gesamt-Chat.
     */
    public String getChatSinceMarker(String marker, int retries, long retryDelayMs) {
        if (marker == null || marker.isEmpty()) {
            log.warn("Kein aktiver Marker - Chat wird aus Datenschutzgruenden leer uebernommen");
            return "";
        }
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                ensureChatOpen();
                List<ChatEntry> entries = readEntries();
                int idx = -1;
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).text().contains(marker)) { idx = i; break; }
                }
                if (idx >= 0) {
                    return String.join("\n", entries.subList(idx + 1, entries.size()).stream()
                            .filter(e -> !e.own())
                            .map(ChatOps::format)
                            .toList()).trim();
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
            Object result = page.evaluate(BrowserScripts.load(BrowserScripts.START_COMMAND_INFO),
                    Map.of("cmd", escaped, "sent", List.copyOf(sentTexts)));
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
