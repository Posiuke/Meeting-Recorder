package bbbbot.recording;

import bbbbot.config.AppProperties;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureServiceTest {

    @TempDir
    Path storageRoot;

    private final Map<UUID, Recording> stored = new HashMap<>();

    private RecordingRepo recordingRepo;
    private SettingsService settings;
    private RecordingService recordingService;
    private CaptureService service;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setup() {
        recordingRepo = mock(RecordingRepo.class);
        settings = mock(SettingsService.class);
        recordingService = mock(RecordingService.class);

        AppProperties props = new AppProperties();
        props.getStorage().setRootDir(storageRoot.toString());

        when(recordingRepo.save(any(Recording.class))).thenAnswer(inv -> {
            Recording r = inv.getArgument(0);
            stored.put(r.getId(), r);
            return r;
        });
        when(recordingRepo.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0))));
        when(recordingRepo.findBySourceAndStatus(any(), any())).thenAnswer(inv -> {
            Recording.Source source = inv.getArgument(0);
            Recording.Status status = inv.getArgument(1);
            List<Recording> matches = new ArrayList<>();
            for (Recording r : stored.values()) {
                if (r.getSource() == source && r.getStatus() == status) matches.add(r);
            }
            return matches;
        });

        when(settings.getBool(SettingsService.CAPTURE_ENABLED)).thenReturn(true);
        when(settings.getBool(SettingsService.WHISPER_DIARIZE)).thenReturn(false);
        when(settings.getLong(SettingsService.CAPTURE_MAX_MEGABYTES)).thenReturn(1L);
        when(settings.getLong(SettingsService.CAPTURE_STALE_MINUTES)).thenReturn(5L);
        // Kleine Schwelle, damit die Testdaten als verwertbar gelten.
        when(settings.getLong(SettingsService.RECORDING_MIN_AUDIO_BYTES)).thenReturn(10L);
        when(recordingService.hasVideoStream(any())).thenReturn(false);

        service = new CaptureService(props, settings, recordingRepo, recordingService);
    }

    private Recording start() throws Exception {
        return service.start(owner, "Test", true, false, false, true, "video/webm;codecs=vp9,opus");
    }

    private void append(UUID id, int seq, String data) throws Exception {
        service.append(id, owner, seq, new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    private Path captureFile(Recording recording) {
        return Path.of(recording.getDirectory()).resolve("capture.webm");
    }

    @Test
    void haengtStueckeInDerReihenfolgeAneinander() throws Exception {
        Recording recording = start();
        append(recording.getId(), 0, "erstes-");
        append(recording.getId(), 1, "zweites");

        assertThat(Files.readString(captureFile(recording))).isEqualTo("erstes-zweites");
    }

    @Test
    void verwirftDoppeltGesendetesStueck() throws Exception {
        Recording recording = start();
        append(recording.getId(), 0, "abc");
        // Netz-Wiederholung nach verlorener Antwort: dieselbe Nummer noch einmal
        append(recording.getId(), 0, "abc");
        append(recording.getId(), 1, "def");

        assertThat(Files.readString(captureFile(recording))).isEqualTo("abcdef");
    }

    @Test
    void lehntLueckeInDerReihenfolgeAb() throws Exception {
        Recording recording = start();
        append(recording.getId(), 0, "abc");

        assertThatThrownBy(() -> append(recording.getId(), 2, "ghi"))
                .isInstanceOf(CaptureService.SequenceMismatchException.class)
                .satisfies(e -> assertThat(((CaptureService.SequenceMismatchException) e).expectedSeq())
                        .isEqualTo(1));
        assertThat(Files.readString(captureFile(recording))).isEqualTo("abc");
    }

    @Test
    void lehntFremdeAufnahmeAb() throws Exception {
        Recording recording = start();
        assertThatThrownBy(() -> service.append(recording.getId(), UUID.randomUUID(), 0,
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void stopUebergibtAnDieVerarbeitung() throws Exception {
        Recording recording = start();
        append(recording.getId(), 0, "genug-daten-fuer-die-schwelle");

        Recording finished = service.stop(recording.getId(), owner);

        assertThat(finished.getStatus()).isEqualTo(Recording.Status.FINALIZING);
        assertThat(finished.getEndedAt()).isNotNull();
        verify(recordingService, times(1))
                .processSourceFile(eq(recording.getId()), eq(captureFile(recording)), eq(false), eq(false));
        assertThat(service.openSessions()).isZero();
    }

    @Test
    void stopOhneDatenMeldetFehlschlag() throws Exception {
        Recording recording = start();

        Recording finished = service.stop(recording.getId(), owner);

        assertThat(finished.getStatus()).isEqualTo(Recording.Status.FAILED);
        assertThat(finished.getDiscardReason()).contains("keine verwertbaren Aufnahmedaten");
        verify(recordingService, never()).processSourceFile(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void zweiterStopSchlaegtFehl() throws Exception {
        Recording recording = start();
        append(recording.getId(), 0, "genug-daten-fuer-die-schwelle");
        service.stop(recording.getId(), owner);

        assertThatThrownBy(() -> service.stop(recording.getId(), owner))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void haeltSichAnDieGroessengrenze() throws Exception {
        Recording recording = start();
        String zuViel = "x".repeat(1024 * 1024 + 1); // Grenze im Test: 1 MB

        assertThatThrownBy(() -> append(recording.getId(), 0, zuViel))
                .isInstanceOf(CaptureService.CaptureTooLargeException.class);
    }

    @Test
    void rettetAbgebrocheneAufnahme() throws Exception {
        Recording recording = start();
        append(recording.getId(), 0, "genug-daten-fuer-die-schwelle");
        // Browser weg: letzter Chunk liegt lange zurueck
        recording.setCaptureLastChunkAt(Instant.now().minus(30, ChronoUnit.MINUTES));

        service.sweepStale();

        assertThat(stored.get(recording.getId()).getStatus()).isEqualTo(Recording.Status.FINALIZING);
        verify(recordingService, times(1))
                .processSourceFile(eq(recording.getId()), eq(captureFile(recording)), eq(false), eq(false));
        assertThat(service.openSessions()).isZero();
    }

    @Test
    void laesstLaufendeAufnahmeInRuhe() throws Exception {
        Recording recording = start();
        append(recording.getId(), 0, "genug-daten-fuer-die-schwelle");

        service.sweepStale();

        assertThat(stored.get(recording.getId()).getStatus()).isEqualTo(Recording.Status.RECORDING);
        verify(recordingService, never()).processSourceFile(any(), any(), anyBoolean(), anyBoolean());
        assertThat(service.openSessions()).isEqualTo(1);
    }

    @Test
    void abortLoeschtAlleDaten() throws Exception {
        Recording recording = start();
        append(recording.getId(), 0, "abc");
        Path dir = Path.of(recording.getDirectory());

        service.abort(recording.getId(), owner);

        assertThat(dir).doesNotExist();
        verify(recordingRepo, times(1)).delete(recording);
        assertThat(service.openSessions()).isZero();
    }

    @Test
    void startBrauchtFreischaltung() {
        when(settings.getBool(SettingsService.CAPTURE_ENABLED)).thenReturn(false);

        assertThatThrownBy(this::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nicht freigeschaltet");
    }
}
