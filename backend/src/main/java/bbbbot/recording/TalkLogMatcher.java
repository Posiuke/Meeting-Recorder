package bbbbot.recording;

import bbbbot.stt.TranscriptAssembler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gleicht die Sprechanzeige von BBB (wer hat wann gesprochen) mit den Zeiten
 * der Diarisierung ab: Fuer jedes Sprecher-Label zaehlt, mit wessen
 * Sprechintervallen sich seine Aeusserungen ueberschneiden. Faellt die
 * Redezeit eines Labels ueberwiegend auf eine Person, ist das ein sehr
 * sicherer Namensvorschlag - er beruht auf dem Protokoll, nicht auf dem Inhalt.
 */
public final class TalkLogMatcher {

    /** Sprechintervall aus dem Protokoll des Bots (Sekunden ab Aufnahmestart). */
    public record Interval(double start, double end, String name) {}

    public record Match(String name, String confidence, String evidence) {}

    /** Eine Aeusserung ohne Folgezeile gilt hoechstens so lange. */
    private static final double MAX_UTTERANCE_SECONDS = 30;
    /** Mindestens so viel ueberlappende Redezeit fuer einen Vorschlag. */
    private static final double MIN_OVERLAP_SECONDS = 5;
    private static final double HIGH_MIN_OVERLAP_SECONDS = 15;
    private static final double HIGH_SHARE = 0.7;
    private static final double MEDIUM_SHARE = 0.5;

    private TalkLogMatcher() {}

    /** Parst das Protokoll ("start\tende\tName" je Zeile); kaputte Zeilen entfallen. */
    public static List<Interval> parse(String talkLog) {
        List<Interval> intervals = new ArrayList<>();
        if (talkLog == null) return intervals;
        for (String line : talkLog.split("\\R")) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 3 || parts[2].isBlank()) continue;
            try {
                double start = Double.parseDouble(parts[0]);
                double end = Double.parseDouble(parts[1]);
                if (end > start) intervals.add(new Interval(start, end, parts[2].trim()));
            } catch (NumberFormatException ignored) {
                // Zeile ueberspringen
            }
        }
        return intervals;
    }

    /**
     * @param entries Transkript-Eintraege mit Sprecher-Labels und absoluter Startzeit
     * @return Vorschlag je Label (nur Labels mit ausreichend eindeutiger Zuordnung)
     */
    public static Map<String, Match> match(List<TranscriptAssembler.Entry> entries, List<Interval> intervals) {
        Map<String, Map<String, Double>> overlapByLabel = new LinkedHashMap<>();
        Map<String, Double> speechByLabel = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            TranscriptAssembler.Entry entry = entries.get(i);
            if (entry.speaker() == null) continue;
            double start = entry.startSeconds();
            double next = i + 1 < entries.size() ? entries.get(i + 1).startSeconds() : start + MAX_UTTERANCE_SECONDS;
            double end = Math.min(Math.max(next, start + 1), start + MAX_UTTERANCE_SECONDS);
            speechByLabel.merge(entry.speaker(), end - start, Double::sum);
            Map<String, Double> overlaps = overlapByLabel.computeIfAbsent(entry.speaker(), k -> new HashMap<>());
            for (Interval interval : intervals) {
                double overlap = Math.min(end, interval.end()) - Math.max(start, interval.start());
                if (overlap > 0) overlaps.merge(interval.name(), overlap, Double::sum);
            }
        }

        Map<String, Match> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : overlapByLabel.entrySet()) {
            String best = null;
            double bestOverlap = 0;
            double secondOverlap = 0;
            for (Map.Entry<String, Double> o : e.getValue().entrySet()) {
                if (o.getValue() > bestOverlap) {
                    secondOverlap = bestOverlap;
                    best = o.getKey();
                    bestOverlap = o.getValue();
                } else if (o.getValue() > secondOverlap) {
                    secondOverlap = o.getValue();
                }
            }
            double speech = speechByLabel.getOrDefault(e.getKey(), 0.0);
            if (best == null || speech <= 0 || bestOverlap < MIN_OVERLAP_SECONDS) continue;
            double share = Math.min(1.0, bestOverlap / speech);
            // Spricht eine zweite Person fast genauso viel mit (Ueberlappung,
            // Durcheinanderreden), ist die Zuordnung nicht eindeutig.
            boolean clearWinner = bestOverlap >= 2 * secondOverlap;
            String confidence;
            if (share >= HIGH_SHARE && bestOverlap >= HIGH_MIN_OVERLAP_SECONDS && clearWinner) {
                confidence = "HIGH";
            } else if (share >= MEDIUM_SHARE && clearWinner) {
                confidence = "MEDIUM";
            } else {
                continue;
            }
            String evidence = "BBB-Sprechanzeige: %s sprach während %d %% der Redezeit dieses Sprechers (%s)"
                    .formatted(best, Math.round(share * 100), TranscriptAssembler.formatTime(Math.round(bestOverlap)));
            result.put(e.getKey(), new Match(best, confidence, evidence));
        }
        return result;
    }
}
