package bbbbot.bot;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Ein Termin aus dem Zeitplan einer Bot-Vorlage: "an diesen Wochentagen von
 * Start- bis Endzeit" in einer festen Zeitzone.
 *
 * <p>Liegt die Endzeit vor (oder auf) der Startzeit, laeuft der Termin ueber
 * Mitternacht und endet am Folgetag - der Wochentag ist der des Beginns.
 * Sommer-/Winterzeit uebernimmt {@link ZonedDateTime}: Faellt eine Startzeit in
 * die uebersprungene Stunde, beginnt der Termin eine Stunde spaeter.
 *
 * @param start Beginn des Termins
 * @param end   Ende des Termins (exklusiv)
 */
public record ScheduleWindow(Instant start, Instant end) {

    public boolean contains(Instant now) {
        return !now.isBefore(start) && now.isBefore(end);
    }

    /**
     * Der Termin, der zu {@code now} gerade laeuft, falls einer laeuft. Geprueft
     * werden der heutige und - fuer Termine ueber Mitternacht - der gestrige Tag.
     */
    public static Optional<ScheduleWindow> active(Set<DayOfWeek> days, LocalTime startTime,
                                                  LocalTime endTime, ZoneId zone, Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        for (LocalDate day : new LocalDate[] {today, today.minusDays(1)}) {
            if (!days.contains(day.getDayOfWeek())) continue;
            ScheduleWindow window = on(day, startTime, endTime, zone);
            if (window.contains(now)) return Optional.of(window);
        }
        return Optional.empty();
    }

    /**
     * Der naechste Termin, der nach {@code now} beginnt oder gerade laeuft -
     * fuer die Anzeige "naechster Termin" in der Oberflaeche.
     */
    public static Optional<ScheduleWindow> next(Set<DayOfWeek> days, LocalTime startTime,
                                                LocalTime endTime, ZoneId zone, Instant now) {
        if (days.isEmpty()) return Optional.empty();
        Optional<ScheduleWindow> running = active(days, startTime, endTime, zone, now);
        if (running.isPresent()) return running;
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int i = 0; i <= 7; i++) {
            LocalDate day = today.plusDays(i);
            if (!days.contains(day.getDayOfWeek())) continue;
            ScheduleWindow window = on(day, startTime, endTime, zone);
            if (window.start().isAfter(now)) return Optional.of(window);
        }
        return Optional.empty();
    }

    static ScheduleWindow on(LocalDate day, LocalTime startTime, LocalTime endTime, ZoneId zone) {
        ZonedDateTime start = day.atTime(startTime).atZone(zone);
        LocalDate endDay = endTime.isAfter(startTime) ? day : day.plusDays(1);
        ZonedDateTime end = endDay.atTime(endTime).atZone(zone);
        return new ScheduleWindow(start.toInstant(), end.toInstant());
    }
}
