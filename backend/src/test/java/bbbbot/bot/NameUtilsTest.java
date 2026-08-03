package bbbbot.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameUtilsTest {

    @Test
    void entferntKlammerSuffixeUndDuplikate() {
        List<String> result = NameUtils.normalizeNames(List.of(
                "Max Mustermann (Moderator)", "Max Mustermann", "  Max   Mustermann "));
        assertEquals(List.of("Max Mustermann"), result);
    }

    @Test
    void filtertUrlsZahlenUndChatLabels() {
        List<String> result = NameUtils.normalizeNames(List.of(
                "https://example.com", "12345", "Öffentlicher Chat", "public chat", "Anna"));
        assertEquals(List.of("Anna"), result);
    }

    @Test
    void sameNameIgnoriertAkzenteUndGrossschreibung() {
        assertTrue(NameUtils.sameName("Müller", "müller"));
        assertTrue(NameUtils.sameName("RecorderBot", "recorderbot"));
        assertFalse(NameUtils.sameName("Anna", "Bernd"));
    }

    @Test
    void erkenntBotNamen() {
        assertTrue(NameUtils.isNameLikeBot("RecorderBot", "RecorderBot"));
        assertTrue(NameUtils.isNameLikeBot("RecorderBot (Zuhörer)", "RecorderBot"));
        assertFalse(NameUtils.isNameLikeBot("Max Mustermann", "RecorderBot"));
        assertFalse(NameUtils.isNameLikeBot("", "RecorderBot"));
    }
}
