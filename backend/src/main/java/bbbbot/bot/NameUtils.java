package bbbbot.bot;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Namens-Normalisierung fuer die Teilnehmerliste (Portierung von
 * src/participants/info.ts): entfernt Klammer-Suffixe, Duplikate, URLs,
 * reine Zahlen und Chat-Beschriftungen; akzent-unabhaengiger Vergleich.
 */
public final class NameUtils {

    private NameUtils() {}

    public static boolean sameName(String a, String b) {
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY); // Akzent-unabhaengig, case-insensitive
        return collator.compare(a, b) == 0;
    }

    public static List<String> normalizeNames(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            if (n == null) continue;
            String cleaned = n.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim().replaceAll("\\s+", " ");
            if (cleaned.isEmpty()) continue;
            if (cleaned.matches("(?i)^https?:.*")) continue;
            if (cleaned.matches("^\\d+$")) continue;
            if (cleaned.matches("(?iu)^(öffentlicher chat|public chat|chat)$")) continue;
            out.add(cleaned);
        }
        return new ArrayList<>(out);
    }

    static String normalizeForSearch(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[\\u00A0\\s]+", " ")
                .replaceAll("(?i)[^a-z0-9äöüß \\-]", "")
                .trim();
    }

    /** Erkennt, ob ein Teilnehmername der Bot selbst ist (fuer Praesenz-Check und Selbst-Ausschluss). */
    public static boolean isNameLikeBot(String candidate, String botName) {
        if (candidate == null || candidate.isEmpty() || botName == null || botName.isEmpty()) return false;
        String c = normalizeForSearch(candidate);
        String b = normalizeForSearch(botName);
        if (c.equals(b) || c.contains(b)) return true;
        for (String token : c.split("[\\s\\-|/]+")) {
            if (token.trim().equals(b)) return true;
        }
        return false;
    }
}
