package bbbbot.llm;

import java.util.ArrayList;
import java.util.List;

/** Teilt langen Kontext in Bloecke fuer die Map-Reduce-Zusammenfassung. */
public final class TextChunker {

    private TextChunker() {}

    public static List<String> chunk(String text, int chunkChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        if (chunkChars <= 0) {
            chunks.add(text);
            return chunks;
        }
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + chunkChars, text.length());
            // Moeglichst an einer Zeilengrenze trennen
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                if (newline > pos + chunkChars / 2) {
                    end = newline + 1;
                }
            }
            chunks.add(text.substring(pos, end));
            pos = end;
        }
        return chunks;
    }
}
