package bbbbot.stt.transcribe;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Wandelt das Ergebnis-JSON von Amazon Transcribe in Aeusserungen um: Woerter
 * mit Zeit und Sprecher ({@code results.items}) werden zu Saetzen je Sprecher
 * zusammengefasst. Ein Satz endet am Satzzeichen, bei einem Sprecherwechsel oder
 * nach einer langen Pause; ueberlange Saetze ohne Satzzeichen werden geteilt,
 * damit das Transkript lesbar und die Sprungmarken brauchbar bleiben.
 */
public final class TranscribeResult {

    /** Eine Aeusserung; Zeiten in Sekunden ab Dateibeginn, speaker z.B. "SPEAKER_00" oder null. */
    public record Utterance(double start, double end, String speaker, String text) {}

    /** Ab dieser Pause zwischen zwei Woertern beginnt eine neue Zeile. */
    static final double PAUSE_SECONDS = 2.0;
    /** Laenger wird eine Zeile ohne Satzzeichen nicht. */
    static final double MAX_UTTERANCE_SECONDS = 30.0;

    private TranscribeResult() {}

    public static List<Utterance> parse(JsonNode root) {
        JsonNode items = root.path("results").path("items");
        List<Utterance> out = new ArrayList<>();
        StringBuilder text = null;
        double start = 0;
        double end = 0;
        String speaker = null;
        for (JsonNode item : items) {
            String content = item.path("alternatives").path(0).path("content").asText("");
            if (content.isEmpty()) continue;
            if ("punctuation".equals(item.path("type").asText())) {
                if (text != null) {
                    text.append(content);
                    if (isSentenceEnd(content)) {
                        out.add(new Utterance(start, end, speaker, text.toString()));
                        text = null;
                    }
                }
                continue;
            }
            double wordStart = item.path("start_time").asDouble(end);
            double wordEnd = item.path("end_time").asDouble(wordStart);
            String wordSpeaker = item.hasNonNull("speaker_label") ? label(item.get("speaker_label").asText()) : null;
            boolean newLine = text == null
                    || !java.util.Objects.equals(wordSpeaker, speaker)
                    || wordStart - end > PAUSE_SECONDS
                    || wordStart - start > MAX_UTTERANCE_SECONDS;
            if (newLine) {
                if (text != null) out.add(new Utterance(start, end, speaker, text.toString()));
                text = new StringBuilder(content);
                start = wordStart;
                speaker = wordSpeaker;
            } else {
                text.append(' ').append(content);
            }
            end = wordEnd;
        }
        if (text != null) out.add(new Utterance(start, end, speaker, text.toString()));
        return out;
    }

    /** Erkannte Sprache (bei automatischer Erkennung), sonst null. */
    public static String languageCode(JsonNode root) {
        JsonNode code = root.path("results").path("language_code");
        return code.isTextual() ? code.asText() : null;
    }

    private static boolean isSentenceEnd(String punctuation) {
        return punctuation.equals(".") || punctuation.equals("?") || punctuation.equals("!");
    }

    /** spk_0 -> SPEAKER_00, passend zu den Labels der anderen Anbieter. */
    static String label(String transcribeLabel) {
        String digits = transcribeLabel.replaceAll("\\D", "");
        if (digits.isEmpty()) return transcribeLabel;
        return "SPEAKER_%02d".formatted(Integer.parseInt(digits));
    }
}
