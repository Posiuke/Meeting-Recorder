package bbbbot.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    @Test
    void kurzerTextBleibtEinChunk() {
        List<String> chunks = TextChunker.chunk("Hallo Welt", 100);
        assertEquals(1, chunks.size());
        assertEquals("Hallo Welt", chunks.get(0));
    }

    @Test
    void langerTextWirdGeteiltUndBleibtVollstaendig() {
        String text = ("Zeile mit etwas Inhalt\n").repeat(100);
        List<String> chunks = TextChunker.chunk(text, 300);
        assertTrue(chunks.size() > 1);
        assertEquals(text, String.join("", chunks));
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 300);
        }
    }

    @Test
    void trenntBevorzugtAnZeilengrenzen() {
        String text = "a".repeat(200) + "\n" + "b".repeat(200);
        List<String> chunks = TextChunker.chunk(text, 250);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).endsWith("\n"));
    }

    @Test
    void leererTextGibtKeineChunks() {
        assertTrue(TextChunker.chunk("", 100).isEmpty());
        assertTrue(TextChunker.chunk(null, 100).isEmpty());
    }
}
