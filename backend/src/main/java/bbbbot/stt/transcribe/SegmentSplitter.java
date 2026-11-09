package bbbbot.stt.transcribe;

import bbbbot.stt.TranscriptAssembler;

import java.util.ArrayList;
import java.util.List;

/**
 * Verteilt das Transkript EINER Datei, die aus mehreren aneinandergehaengten
 * Segmenten besteht, wieder auf die Segmente - im Format, das
 * {@link TranscriptAssembler} je Segment erwartet (Zeiten relativ zum Segment,
 * Sprecher-Kopfzeilen).
 *
 * <p>Wozu: Transcribe kennt bei einem Auftrag nur diese eine Datei. Wird die
 * ganze Aufnahme am Stueck geschickt, sind die Sprecher ueber die gesamte Laenge
 * dieselben - pro Segment einzeln vergaebe jeder Auftrag spk_0, spk_1 neu.
 */
public final class SegmentSplitter {

    private SegmentSplitter() {}

    /**
     * @param utterances   Aeusserungen mit Zeiten ab Beginn der zusammengefuegten Datei
     * @param durationsMs  Dauer der Segmente in Reihenfolge, wie sie aneinandergehaengt wurden
     * @return Transkript-Text je Segment (gleiche Reihenfolge; leer, wenn dort niemand sprach)
     */
    public static List<String> split(List<TranscribeResult.Utterance> utterances, List<Long> durationsMs) {
        int n = durationsMs.size();
        List<StringBuilder> texts = new ArrayList<>();
        List<String> lastSpeaker = new ArrayList<>();
        double[] exactStart = new double[n];
        long[] shownOffset = new long[n];
        double exact = 0;
        long shown = 0;
        for (int i = 0; i < n; i++) {
            texts.add(new StringBuilder());
            lastSpeaker.add(null);
            exactStart[i] = exact;
            // TranscriptAssembler verschiebt um die GERUNDETEN Segmentdauern - die
            // relative Zeit muss dazu passen, sonst laufen die Anzeigen auseinander.
            shownOffset[i] = shown;
            exact += durationsMs.get(i) / 1000.0;
            shown += Math.round(durationsMs.get(i) / 1000.0);
        }
        for (TranscribeResult.Utterance u : utterances) {
            int seg = 0;
            while (seg < n - 1 && u.start() >= exactStart[seg + 1]) seg++;
            long relative = Math.max(0, Math.round(Math.floor(u.start())) - shownOffset[seg]);
            StringBuilder sb = texts.get(seg);
            if (u.speaker() != null && !u.speaker().equals(lastSpeaker.get(seg))) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(u.speaker()).append(":\n");
                lastSpeaker.set(seg, u.speaker());
            }
            sb.append('[').append(TranscriptAssembler.formatTime(relative)).append("] ")
                    .append(u.text()).append('\n');
        }
        return texts.stream().map(sb -> sb.toString().trim()).toList();
    }
}
