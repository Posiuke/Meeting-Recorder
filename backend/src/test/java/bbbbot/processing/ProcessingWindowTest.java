package bbbbot.processing;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingWindowTest {

    private static final LocalTime START = LocalTime.of(20, 0);
    private static final LocalTime END = LocalTime.of(6, 0);

    @Test
    void fensterUeberMitternacht() {
        assertTrue(ProcessingWindow.isWithinWindow(LocalTime.of(22, 0), START, END));
        assertTrue(ProcessingWindow.isWithinWindow(LocalTime.of(3, 30), START, END));
        assertTrue(ProcessingWindow.isWithinWindow(LocalTime.of(20, 0), START, END));
        assertFalse(ProcessingWindow.isWithinWindow(LocalTime.of(6, 0), START, END));
        assertFalse(ProcessingWindow.isWithinWindow(LocalTime.of(12, 0), START, END));
        assertFalse(ProcessingWindow.isWithinWindow(LocalTime.of(19, 59), START, END));
    }

    @Test
    void fensterInnerhalbEinesTages() {
        LocalTime s = LocalTime.of(9, 0);
        LocalTime e = LocalTime.of(17, 0);
        assertTrue(ProcessingWindow.isWithinWindow(LocalTime.of(12, 0), s, e));
        assertFalse(ProcessingWindow.isWithinWindow(LocalTime.of(18, 0), s, e));
        assertFalse(ProcessingWindow.isWithinWindow(LocalTime.of(8, 59), s, e));
    }

    @Test
    void identischeZeitenBedeutenRundUmDieUhr() {
        assertTrue(ProcessingWindow.isWithinWindow(LocalTime.of(12, 0), START, START));
    }
}
