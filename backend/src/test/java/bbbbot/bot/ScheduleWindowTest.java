package bbbbot.bot;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Termine aus dem Zeitplan einer Bot-Vorlage: Wochentage, Uhrzeiten, Zeitzone. */
class ScheduleWindowTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Set<DayOfWeek> MO_MI_FR =
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);

    /** 2026-09-21 ist ein Montag. */
    private static Instant berlin(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    @Test
    void terminLaeuftNurAnGewaehltenTagenInnerhalbDerZeiten() {
        LocalTime start = LocalTime.of(9, 0);
        LocalTime end = LocalTime.of(10, 0);

        assertThat(ScheduleWindow.active(MO_MI_FR, start, end, BERLIN, berlin(21, 8, 59))).isEmpty();
        assertThat(ScheduleWindow.active(MO_MI_FR, start, end, BERLIN, berlin(21, 9, 0)))
                .hasValue(new ScheduleWindow(berlin(21, 9, 0), berlin(21, 10, 0)));
        assertThat(ScheduleWindow.active(MO_MI_FR, start, end, BERLIN, berlin(21, 9, 59))).isPresent();
        // Ende ist exklusiv
        assertThat(ScheduleWindow.active(MO_MI_FR, start, end, BERLIN, berlin(21, 10, 0))).isEmpty();
        // Dienstag ist nicht gewaehlt
        assertThat(ScheduleWindow.active(MO_MI_FR, start, end, BERLIN, berlin(22, 9, 30))).isEmpty();
    }

    /** Uhrzeiten gelten in der Zeitzone der Vorlage, nicht in der des Servers (oft UTC). */
    @Test
    void uhrzeitenGeltenInDerZeitzoneDerVorlage() {
        Instant neunUhrUtc = ZonedDateTime.of(2026, 9, 21, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant();
        // 09:00 UTC = 11:00 in Berlin (Sommerzeit) - ausserhalb von 09-10 Uhr Berliner Zeit
        assertThat(ScheduleWindow.active(MO_MI_FR, LocalTime.of(9, 0), LocalTime.of(10, 0),
                BERLIN, neunUhrUtc)).isEmpty();
        assertThat(ScheduleWindow.active(MO_MI_FR, LocalTime.of(9, 0), LocalTime.of(10, 0),
                ZoneId.of("UTC"), neunUhrUtc)).isPresent();
    }

    /** Endzeit vor Startzeit: Der Termin endet am Folgetag; der Wochentag ist der des Beginns. */
    @Test
    void terminUeberMitternacht() {
        Set<DayOfWeek> montag = EnumSet.of(DayOfWeek.MONDAY);
        LocalTime start = LocalTime.of(22, 0);
        LocalTime end = LocalTime.of(1, 0);

        assertThat(ScheduleWindow.active(montag, start, end, BERLIN, berlin(21, 23, 0)))
                .hasValue(new ScheduleWindow(berlin(21, 22, 0), berlin(22, 1, 0)));
        // Dienstag 00:30 gehoert noch zum Montagstermin
        assertThat(ScheduleWindow.active(montag, start, end, BERLIN, berlin(22, 0, 30))).isPresent();
        assertThat(ScheduleWindow.active(montag, start, end, BERLIN, berlin(22, 1, 0))).isEmpty();
        // Sonntag 23:00 ist kein Termin
        assertThat(ScheduleWindow.active(montag, start, end, BERLIN, berlin(20, 23, 0))).isEmpty();
    }

    @Test
    void naechsterTermin() {
        LocalTime start = LocalTime.of(9, 0);
        LocalTime end = LocalTime.of(10, 0);

        // Montag 10:30 -> Mittwoch 09:00
        assertThat(ScheduleWindow.next(MO_MI_FR, start, end, BERLIN, berlin(21, 10, 30)))
                .hasValue(new ScheduleWindow(berlin(23, 9, 0), berlin(23, 10, 0)));
        // Laufender Termin ist der naechste
        assertThat(ScheduleWindow.next(MO_MI_FR, start, end, BERLIN, berlin(21, 9, 30)))
                .hasValue(new ScheduleWindow(berlin(21, 9, 0), berlin(21, 10, 0)));
        // Freitag 11:00 -> Montag der Folgewoche
        assertThat(ScheduleWindow.next(MO_MI_FR, start, end, BERLIN, berlin(25, 11, 0)))
                .hasValue(new ScheduleWindow(berlin(28, 9, 0), berlin(28, 10, 0)));
        // Nur ein Wochentag, der heute schon vorbei ist -> in sieben Tagen
        assertThat(ScheduleWindow.next(EnumSet.of(DayOfWeek.MONDAY), start, end, BERLIN, berlin(21, 11, 0)))
                .hasValue(new ScheduleWindow(berlin(28, 9, 0), berlin(28, 10, 0)));
        assertThat(ScheduleWindow.next(EnumSet.noneOf(DayOfWeek.class), start, end, BERLIN, berlin(21, 11, 0)))
                .isEmpty();
    }

    /** Zeitumstellung (25.10.2026): Ein Termin 02:00-03:30 dauert in dieser Nacht 2,5 Stunden. */
    @Test
    void zeitumstellungWirdBeruecksichtigt() {
        Set<DayOfWeek> sonntag = EnumSet.of(DayOfWeek.SUNDAY);
        Instant mitten = ZonedDateTime.of(2026, 10, 25, 3, 0, 0, 0, BERLIN).toInstant();
        var window = ScheduleWindow.active(sonntag, LocalTime.of(1, 0), LocalTime.of(3, 30), BERLIN, mitten);
        assertThat(window).isPresent();
        assertThat(java.time.Duration.between(window.get().start(), window.get().end()).toMinutes())
                .isEqualTo(210);
    }
}
