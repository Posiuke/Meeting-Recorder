package bbbbot.bot;

import bbbbot.config.AppProperties;
import bbbbot.domain.BotSession;
import bbbbot.recording.RecordingService;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ein Bot, der nach Zeitplan laeuft, laesst sich verlaengern - ab dem
 * bisherigen Ende, oder ganz ohne Ende.
 */
class BotScheduleExtendTest {

    private static final Instant NOW = Instant.parse("2026-09-21T08:00:00Z");

    private BotSessionRepo sessionRepo;
    private BotManager botManager;
    private BotInstance bot;
    private BotSession session;
    private UUID sessionId;

    @BeforeEach
    void setup() throws Exception {
        sessionRepo = mock(BotSessionRepo.class);
        botManager = new BotManager(new AppProperties(), mock(SettingsService.class),
                mock(RecordingService.class), sessionRepo);
        session = BotSession.create("https://bbb.example.org/b/jf", "RecorderBot",
                UUID.randomUUID(), true, false, true, false);
        sessionId = session.getId();
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        bot = mock(BotInstance.class);
        register(bot);
    }

    @SuppressWarnings("unchecked")
    private void register(BotInstance instance) throws Exception {
        Field f = BotManager.class.getDeclaredField("instances");
        f.setAccessible(true);
        ((Map<UUID, BotInstance>) f.get(botManager)).put(sessionId, instance);
    }

    @Test
    void verlaengertAbDemBisherigenEnde() {
        Instant end = NOW.plus(Duration.ofMinutes(10));
        when(bot.getScheduledStopAt()).thenReturn(end);

        Instant next = botManager.extendScheduledStop(sessionId, Duration.ofMinutes(30), NOW);

        assertThat(next).isEqualTo(end.plus(Duration.ofMinutes(30)));
        verify(bot).setScheduledStopAt(next);
        assertThat(session.getScheduledStopAt()).isEqualTo(next);
        verify(sessionRepo).save(session);
    }

    /** Ende schon verstrichen, Bot aber noch nicht weg: ab jetzt zaehlen. */
    @Test
    void verlaengertAbJetztWennDasEndeSchonVorbeiIst() {
        when(bot.getScheduledStopAt()).thenReturn(NOW.minusSeconds(20));

        Instant next = botManager.extendScheduledStop(sessionId, Duration.ofMinutes(15), NOW);

        assertThat(next).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void ohneEndeHebtDasGeplanteEndeAuf() {
        session.setScheduledStopAt(NOW.plusSeconds(60));
        when(bot.getScheduledStopAt()).thenReturn(NOW.plusSeconds(60));

        Instant next = botManager.extendScheduledStop(sessionId, null, NOW);

        assertThat(next).isNull();
        verify(bot).setScheduledStopAt(null);
        assertThat(session.getScheduledStopAt()).isNull();
    }

    @Test
    void botOhneGeplantesEndeLaesstSichNichtVerlaengern() {
        when(bot.getScheduledStopAt()).thenReturn(null);

        assertThatThrownBy(() -> botManager.extendScheduledStop(sessionId, Duration.ofMinutes(30), NOW))
                .isInstanceOf(IllegalStateException.class);
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void botDerSchonGehtLaesstSichNichtVerlaengern() {
        when(bot.getScheduledStopAt()).thenReturn(NOW.plusSeconds(60));
        when(bot.isShuttingDown()).thenReturn(true);

        assertThatThrownBy(() -> botManager.extendScheduledStop(sessionId, Duration.ofMinutes(30), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Der Scheduler beendet den Bot erst zum verlaengerten Ende. */
    @Test
    void schedulerRichtetSichNachDemVerlaengertenEnde() {
        when(bot.getScheduledStopAt()).thenReturn(NOW.plus(Duration.ofMinutes(30)));
        when(bot.getSessionId()).thenReturn(sessionId);
        BotManager manager = mock(BotManager.class);
        when(manager.listActive()).thenReturn(java.util.List.of(bot));
        BotScheduler scheduler = new BotScheduler(
                mock(bbbbot.repository.Repositories.BotTemplateRepo.class), sessionRepo,
                manager, mock(BotTemplateLauncher.class));

        scheduler.tick(NOW.plus(Duration.ofMinutes(29)));
        verify(manager, never()).stopBot(any());

        scheduler.tick(NOW.plus(Duration.ofMinutes(30)));
        verify(manager).stopBot(sessionId);
    }
}
