package bbbbot.stt;

import bbbbot.domain.RecordingSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fuegt die pro Segment erstellten Whisper-Transkripte zu einem durchgehenden
 * Gesamt-Transkript zusammen. Whisper kennt nur das einzelne MP3-Segment,
 * dessen Zeitstempel daher immer bei [00:00] beginnen - hier werden sie um die
 * aufsummierte Dauer der vorangegangenen Segmente verschoben, sodass ueber die
 * gesamte Aufnahme fortlaufende Zeiten entstehen.
 */
public final class TranscriptAssembler {

    /** Eine Transkript-Zeile mit absoluter Startzeit (Sekunden ab Aufnahmebeginn). */
    public record Entry(long startSeconds, String speaker, String text) {}

    /** [mm:ss] oder [hh:mm:ss] am Zeilenanfang, wie von WhisperClient erzeugt. */
    private static final Pattern TIMESTAMP_LINE =
            Pattern.compile("^\\[(\\d{1,3}):(\\d{2})(?::(\\d{2}))?]\\s*(.*)$");

    /** Sprecher-Kopfzeile der WhisperX-Diarisierung, z.B. "SPEAKER_00:". */
    private static final Pattern SPEAKER_LINE = Pattern.compile("^([^\\[\\]]{1,80}):$");

    private TranscriptAssembler() {}

    /**
     * Parst die Segment-Transkripte (Reihenfolge nach seq) und liefert die
     * zusammengefuegten Eintraege mit fortlaufenden Zeitstempeln - immer das
     * Whisper-Original.
     */
    public static List<Entry> assemble(List<RecordingSegment> segments) {
        return assemble(segments, false);
    }

    /**
     * Wie {@link #assemble(List)}; mit {@code preferCorrected} wird je Segment die
     * geglaettete Fassung genommen, sofern vorhanden (sonst das Original).
     */
    public static List<Entry> assemble(List<RecordingSegment> segments, boolean preferCorrected) {
        List<Entry> entries = new ArrayList<>();
        long offsetSeconds = 0;
        for (RecordingSegment segment : segments) {
            String text = preferCorrected ? segment.getEffectiveTranscript() : segment.getTranscriptText();
            if (text != null && !text.isBlank()) {
                parseSegment(text, offsetSeconds, entries);
            }
            if (segment.getDurationMs() != null) {
                offsetSeconds += Math.round(segment.getDurationMs() / 1000.0);
            }
        }
        return entries;
    }

    private static void parseSegment(String text, long offsetSeconds, List<Entry> entries) {
        String speaker = null;
        int firstEntryOfSegment = entries.size();
        for (String line : text.split("\\R")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            Matcher ts = TIMESTAMP_LINE.matcher(line);
            if (ts.matches()) {
                long seconds = ts.group(3) != null
                        ? Long.parseLong(ts.group(1)) * 3600 + Long.parseLong(ts.group(2)) * 60 + Long.parseLong(ts.group(3))
                        : Long.parseLong(ts.group(1)) * 60 + Long.parseLong(ts.group(2));
                String content = ts.group(4).strip();
                if (!content.isEmpty()) {
                    entries.add(new Entry(offsetSeconds + seconds, speaker, content));
                }
                continue;
            }
            Matcher sp = SPEAKER_LINE.matcher(line);
            if (sp.matches()) {
                speaker = sp.group(1).strip();
                continue;
            }
            // Zeile ohne Zeitstempel (z.B. reines Text-Output-Format): an den
            // letzten Eintrag dieses Segments anhaengen, sonst neuen beginnen.
            if (entries.size() > firstEntryOfSegment) {
                Entry last = entries.remove(entries.size() - 1);
                entries.add(new Entry(last.startSeconds(), last.speaker(), last.text() + " " + line));
            } else {
                entries.add(new Entry(offsetSeconds, speaker, line));
            }
        }
    }

    /**
     * Ersetzt die rohen Diarisierungs-Labels (SPEAKER_00) durch die gepflegten
     * Teilnehmernamen. Labels ohne Zuordnung bleiben unveraendert.
     */
    public static List<Entry> applyNames(List<Entry> entries, java.util.Map<String, String> names) {
        if (names.isEmpty()) return entries;
        return entries.stream()
                .map(e -> {
                    String name = e.speaker() == null ? null : names.get(e.speaker());
                    return name == null ? e : new Entry(e.startSeconds(), name, e.text());
                })
                .toList();
    }

    /** Rendert die Eintraege als lesbaren Text (eine Zeile pro Eintrag). */
    public static String toText(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry entry : entries) {
            sb.append('[').append(formatTime(entry.startSeconds())).append("] ");
            if (entry.speaker() != null) {
                sb.append(entry.speaker()).append(": ");
            }
            sb.append(entry.text()).append('\n');
        }
        return sb.toString().trim();
    }

    /** mm:ss unter einer Stunde, sonst h:mm:ss. */
    public static String formatTime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? "%d:%02d:%02d".formatted(h, m, s) : "%02d:%02d".formatted(m, s);
    }
}
