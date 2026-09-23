package bbbbot.bot;

import bbbbot.domain.BotSession;
import bbbbot.domain.BotTemplate;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.repository.Repositories.BotTemplateRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Der Scheduler startet pro Termin genau einen Bot, versucht es nach einem
 * Fehlschlag erneut und beendet den Bot zur Endzeit - holt ihn aber nicht
 * zurueck, wenn ihn jemand von Hand weggeschickt hat.
 */
class BotSchedulerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** Montag, 21.09.2026, 09:05 Berliner Zeit */
    private static final Instant MONTAG_0905 =
            ZonedDateTime.of(2026, 9, 21, 9, 5, 0, 0, BERLIN).toInstant();
    private static final Instant MONTAG_0900 =
            ZonedDateTime.of(2026, 9, 21, 9, 0, 0, 0, BERLIN).toInstant();
    private static final Instant MONTAG_1000 =
            ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, BERLIN).toInstant();

    private BotTemplateRepo templateRepo;
    private BotSessionRepo sessionRepo;
    private BotManager botManager;
    private BotTemplateLauncher launcher;
    private BotScheduler scheduler;
    private BotTemplate template;

    @BeforeEach
    void setup() {
        templateRepo = mock(BotTemplateRepo.class);
        sessionRepo = mock(BotSessionRepo.class);
        botManager = mock(BotManager.class);
        launcher = mock(BotTemplateLauncher.class);
        scheduler = new BotScheduler(templateRepo, sessionRepo, botManager, launcher);

        template = BotTemplate.create(UUID.randomUUID(), "Jour fixe",
                "https://bbb.example.org/b/jf", "RecorderBot");
        template.setScheduleEnabled(true);
        template.setScheduleDays(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY));
        template.setScheduleStart(LocalTime.of(9, 0));
        template.setScheduleEnd(LocalTime.of(10, 0));
        template.setScheduleTimeZone("Europe/Berlin");
        when(templateRepo.findByScheduleEnabledTrue()).thenReturn(List.of(template));
        when(botManager.listActive()).thenReturn(List.of());
        when(launcher.start(any(), any())).thenReturn(session(BotSession.Status.STARTING));
    }

    private BotSession session(BotSession.Status status) {
        BotSession s = BotSession.create(template.getMeetingUrl(), "RecorderBot",
                template.getOwnerId(), true, false, true, false);
        s.setBotTemplateId(template.getId());
        s.setStatus(status);
        return s;
    }

    private void sessionsImTermin(BotSession... sessions) {
        when(sessionRepo.findByBotTemplateIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                template.getId(), MONTAG_0900)).thenReturn(List.of(sessions));
    }

    @Test
    void startetZuBeginnDesTerminsMitDessenEnde() {
        sessionsImTermin();

        scheduler.tick(MONTAG_0905);

        verify(launcher).start(template, MONTAG_1000);
    }

    @Test
    void startetNichtAusserhalbDesTerminsOderAnAnderenTagen() {
        scheduler.tick(MONTAG_1000);
        scheduler.tick(MONTAG_0900.minusSeconds(60));
        scheduler.tick(MONTAG_0905.plus(Duration.ofDays(1)));

        verify(launcher, never()).start(any(), any());
    }

    @Test
    void startetKeinenZweitenBotWennSchonEinerLaeuft() {
        sessionsImTermin();
        BotInstance running = mock(BotInstance.class);
        when(running.getBotTemplateId()).thenReturn(template.getId());
        when(botManager.listActive()).thenReturn(List.of(running));

        scheduler.tick(MONTAG_0905);

        verify(launcher, never()).start(any(), any());
    }

    /** Von Hand beendet (oder Meeting vorbei): nicht zurueckholen. */
    @Test
    void holtEinenBeendetenBotNichtZurueck() {
        sessionsImTermin(session(BotSession.Status.STOPPED));

        scheduler.tick(MONTAG_0905);

        verify(launcher, never()).start(any(), any());
    }

    @Test
    void versuchtEsNachFehlschlagMitAbstandErneut() {
        BotSession failed = session(BotSession.Status.FAILED);
        failed.setEndedAt(MONTAG_0905.minusSeconds(30));
        sessionsImTermin(failed);

        scheduler.tick(MONTAG_0905);
        verify(launcher, never()).start(any(), any());

        scheduler.tick(MONTAG_0905.plus(BotScheduler.RETRY_DELAY));
        verify(launcher).start(template, MONTAG_1000);
    }

    @Test
    void gibtNachZuVielenFehlversuchenAuf() {
        BotSession[] failed = new BotSession[BotScheduler.MAX_ATTEMPTS_PER_WINDOW];
        for (int i = 0; i < failed.length; i++) {
            failed[i] = session(BotSession.Status.FAILED);
            failed[i].setEndedAt(MONTAG_0900);
        }
        sessionsImTermin(failed);

        scheduler.tick(MONTAG_0905.plus(Duration.ofMinutes(20)));

        verify(launcher, never()).start(any(), any());
    }

    @Test
    void startetNichtWennDerRaumSchonAufgenommenWird() {
        sessionsImTermin();
        when(botManager.isUrlInUse(template.getMeetingUrl())).thenReturn(true);

        scheduler.tick(MONTAG_0905);

        verify(launcher, never()).start(any(), any());
    }

    @Test
    void beendetBotsZurGeplantenEndzeit() {
        when(templateRepo.findByScheduleEnabledTrue()).thenReturn(List.of());
        BotInstance due = mock(BotInstance.class);
        UUID dueId = UUID.randomUUID();
        when(due.getSessionId()).thenReturn(dueId);
        when(due.getScheduledStopAt()).thenReturn(MONTAG_1000);
        BotInstance manual = mock(BotInstance.class);
        when(manual.getSessionId()).thenReturn(UUID.randomUUID());
        when(botManager.listActive()).thenReturn(List.of(due, manual));

        scheduler.tick(MONTAG_0905);
        verify(botManager, never()).stopBot(any());

        scheduler.tick(MONTAG_1000);
        verify(botManager).stopBot(eq(dueId));
        verify(botManager, never()).stopBot(eq(manual.getSessionId()));
    }
}
