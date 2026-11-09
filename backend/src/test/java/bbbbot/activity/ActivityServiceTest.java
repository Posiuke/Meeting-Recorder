package bbbbot.activity;

import bbbbot.bot.BotManager;
import bbbbot.config.AppProperties;
import bbbbot.docs.RecordingDocumentService;
import bbbbot.domain.ProcessingJob;
import bbbbot.processing.ProcessingService;
import bbbbot.recording.CaptureService;
import bbbbot.recording.RecordingService;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityServiceTest {

    private final ProcessingService processing = mock(ProcessingService.class);
    private final ProcessingJobRepo jobRepo = mock(ProcessingJobRepo.class);
    private final CaptureService captures = mock(CaptureService.class);
    private final BotManager bots = mock(BotManager.class);
    private final RecordingService recordings = mock(RecordingService.class);
    private final RecordingDocumentService documents = mock(RecordingDocumentService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private ActivityService service;

    @BeforeEach
    void setUp() {
        when(bots.listActive()).thenReturn(List.of());
        when(jobRepo.findByStatusOrderByCreatedAt(any())).thenReturn(List.of());
        // Fenster, das jetzt gerade sicher geschlossen ist
        LocalTime now = LocalTime.now();
        when(settings.get(SettingsService.PROCESSING_WINDOW_START)).thenReturn(now.plusHours(2).withSecond(0).withNano(0).toString());
        when(settings.get(SettingsService.PROCESSING_WINDOW_END)).thenReturn(now.plusHours(3).withSecond(0).withNano(0).toString());
        service = new ActivityService(processing, jobRepo, captures, bots, recordings, documents, settings);
    }

    @Test
    void ohneArbeitIstDerDienstNichtBeschaeftigt() {
        ActivityService.Report report = service.report();
        assertThat(report.busy()).isFalse();
        assertThat(report.reasons()).isEmpty();
        assertThat(report.nextWindowStart()).isNull();
    }

    @Test
    void laufendeAufnahmeAuswertungUndMedienarbeitHaltenWach() {
        when(captures.openSessions()).thenReturn(1);
        when(processing.isBusy()).thenReturn(true);
        when(recordings.activeMediaWork()).thenReturn(2);

        ActivityService.Report report = service.report();
        assertThat(report.busy()).isTrue();
        assertThat(report.reasons()).containsExactly("processing", "captures", "media");
    }

    @Test
    void sofortJobHaeltWachNachtJobNurAlsHinweisAufsFenster() {
        ProcessingJob now = ProcessingJob.create(UUID.randomUUID(), true);
        ProcessingJob night = ProcessingJob.create(UUID.randomUUID(), false);
        when(jobRepo.findByStatusOrderByCreatedAt(ProcessingJob.Status.PENDING)).thenReturn(List.of(night));

        ActivityService.Report deferredOnly = service.report();
        assertThat(deferredOnly.busy()).as("wartet nur aufs Nachtfenster").isFalse();
        assertThat(deferredOnly.deferredJobs()).isEqualTo(1);
        assertThat(deferredOnly.nextWindowStart()).isAfter(Instant.now());

        when(jobRepo.findByStatusOrderByCreatedAt(ProcessingJob.Status.PENDING)).thenReturn(List.of(now, night));
        assertThat(service.report().reasons()).containsExactly("pendingJobs");
    }

    @Test
    void filterZaehltNutzeranfragenAberKeinPollingUndKeineAbfrage() throws Exception {
        ActivityTrackingFilter filter = new ActivityTrackingFilter(service);
        Instant before = service.report().lastRequestAt();
        Thread.sleep(5);

        run(filter, "/api/internal/activity", false);
        run(filter, "/api/recordings/1", true);
        run(filter, "/assets/app.js", false);
        assertThat(service.report().lastRequestAt()).isEqualTo(before);

        run(filter, "/api/recordings", false);
        assertThat(service.report().lastRequestAt()).isAfter(before);
    }

    @Test
    void endpunktNurMitToken() {
        AppProperties props = new AppProperties();
        ActivityController off = new ActivityController(service, props);
        assertThatThrownBy(() -> off.report("x")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");

        props.getActivity().setToken("geheim");
        ActivityController on = new ActivityController(service, props);
        assertThatThrownBy(() -> on.report("falsch")).hasMessageContaining("401");
        assertThatThrownBy(() -> on.report(null)).hasMessageContaining("401");
        assertThat(on.report("geheim").busy()).isFalse();
    }

    private static void run(ActivityTrackingFilter filter, String path, boolean background) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (background) request.addHeader(ActivityTrackingFilter.BACKGROUND_HEADER, "1");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
