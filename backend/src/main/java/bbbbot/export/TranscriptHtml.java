package bbbbot.export;

import bbbbot.stt.TranscriptAssembler;

import java.util.List;

/**
 * Rendert das zusammengefuehrte Transkript als HTML-Fragment fuer den
 * Word-Export. Bewusst nicht ueber {@link MarkdownHtml}: Das Transkript ist
 * kein Markdown, sondern eine Liste aus Zeitstempel, Sprecher und Text - und
 * genau so soll es im Dokument stehen.
 */
public final class TranscriptHtml {

    private TranscriptHtml() {}

    /**
     * Eine Absatzzeile je Eintrag. Ein Sprecherwechsel bekommt eine eigene
     * Zwischenueberschrift, damit ein langes Gespraech im Dokument lesbar
     * bleibt; ohne Sprechererkennung entfaellt sie ersatzlos.
     */
    public static String toHtml(List<TranscriptAssembler.Entry> entries) {
        StringBuilder out = new StringBuilder();
        String lastSpeaker = null;
        for (TranscriptAssembler.Entry entry : entries) {
            if (entry.speaker() != null && !entry.speaker().equals(lastSpeaker)) {
                out.append("<h3>").append(MarkdownHtml.escape(entry.speaker())).append("</h3>\n");
                lastSpeaker = entry.speaker();
            }
            out.append("<p><span class=\"time\">[")
                    .append(TranscriptAssembler.formatTime(entry.startSeconds()))
                    .append("]</span> ")
                    .append(MarkdownHtml.escape(entry.text()))
                    .append("</p>\n");
        }
        return out.toString();
    }
}
