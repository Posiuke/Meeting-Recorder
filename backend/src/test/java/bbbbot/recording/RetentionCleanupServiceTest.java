package bbbbot.recording;

import bbbbot.bot.BotManager;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionCleanupServiceTest {

    private RecordingRepo recordingRepo;
    private SettingsService settings;
    private BotManager botManager;
    private RetentionCleanupService service;

    @BeforeEach
    void setup() {
        recordingRepo = org.mockito.Mockito.mock(RecordingRepo.class);
        settings = org.mockito.Mockito.mock(SettingsService.class);
        botManager = org.mockito.Mockito.mock(BotManager.class);
        service = new RetentionCleanupService(recordingRepo, settings, botManager);

        when(settings.getBool(SettingsService.CLEANUP_ENABLED)).thenReturn(true);
        when(settings.getLong(SettingsService.CLEANUP_OLDER_THAN_DAYS)).thenReturn(90L);
    }

    private Recording oldRecording(Recording.Status status) {
        Recording r = Recording.start(null, UUID.randomUUID(), null, "/tmp/does-not-exist", false, false, false);
        r.setStatus(status);
        return r;
    }

    @Test
    void loeschtAlteFertigeAufnahme() {
        Recording r = oldRecording(Recording.Status.DONE);
        when(recordingRepo.findByStartedAtBefore(any())).thenReturn(List.of(r));
        when(botManager.isRecordingActive(r.getId())).thenReturn(false);

        service.cleanup();

        verify(recordingRepo, times(1)).delete(r);
    }

    @Test
    void ueberspringtNochLaufendeVerarbeitung() {
        Recording r = oldRecording(Recording.Status.PROCESSING);
        when(recordingRepo.findByStartedAtBefore(any())).thenReturn(List.of(r));

        service.cleanup();

        verify(recordingRepo, never()).delete(any());
    }

    @Test
    void ueberspringtAktivAufnehmende() {
        Recording r = oldRecording(Recording.Status.DONE);
        when(recordingRepo.findByStartedAtBefore(any())).thenReturn(List.of(r));
        when(botManager.isRecordingActive(r.getId())).thenReturn(true);

        service.cleanup();

        verify(recordingRepo, never()).delete(any());
    }

    @Test
    void machtNichtsWennDeaktiviert() {
        when(settings.getBool(SettingsService.CLEANUP_ENABLED)).thenReturn(false);

        service.cleanup();

        verify(recordingRepo, never()).findByStartedAtBefore(any());
        verify(recordingRepo, never()).delete(any());
    }
}
