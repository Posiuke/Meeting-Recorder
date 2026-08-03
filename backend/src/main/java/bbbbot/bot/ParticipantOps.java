package bbbbot.bot;

import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;

/**
 * Teilnehmer-Informationen aus dem BBB-DOM (Portierung von src/participants/info.ts).
 */
public class ParticipantOps {

    public record AttendeeInfo(int total, int others, List<String> names) {}

    private final Page page;

    public ParticipantOps(Page page) {
        this.page = page;
    }

    @SuppressWarnings("unchecked")
    public AttendeeInfo getAttendeeInfo(String botName) {
        List<String> rawNames;
        try {
            Object result = page.evaluate(BrowserScripts.load(BrowserScripts.ATTENDEES));
            rawNames = result instanceof List ? (List<String>) result : List.of();
        } catch (RuntimeException e) {
            rawNames = List.of();
        }
        List<String> cleaned = NameUtils.normalizeNames(rawNames);
        List<String> others = new ArrayList<>();
        for (String name : cleaned) {
            if (!NameUtils.sameName(name, botName)) others.add(name);
        }
        return new AttendeeInfo(cleaned.size(), others.size(), cleaned);
    }

    /** Ist der Bot selbst (noch) in der Teilnehmerliste sichtbar? */
    public boolean isBotPresent(String botName, AttendeeInfo info) {
        for (String name : info.names()) {
            if (NameUtils.isNameLikeBot(name, botName)) return true;
        }
        return false;
    }

    public int remoteAudioTrackCount() {
        try {
            Object result = page.evaluate(BrowserScripts.load(BrowserScripts.AUDIO_STATS));
            if (result instanceof Number n) return n.intValue();
        } catch (RuntimeException ignored) {
        }
        return 0;
    }
}
