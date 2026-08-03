package bbbbot.recording;

import bbbbot.config.AppProperties;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nimmt Bildschirmaufnahmen entgegen, die im Browser des Nutzers entstehen
 * (getDisplayMedia + MediaRecorder). Der Browser liefert die laufende Aufnahme
 * in kurzen Stuecken; die werden hier in EINE Datei angehaengt und am Ende in
 * dieselbe Verarbeitungsstrecke gegeben wie ein Datei-Upload.
 *
 * <p>Warum stueckweise und nicht ein Upload am Ende: Eine mehrstuendige Sitzung
 * waere sonst ein Gigabyte im Browser-Speicher, das ein Absturz komplett
 * vernichtet. So liegt jederzeit alles bis zum letzten Stueck auf dem Server -
 * bricht der Browser weg, rettet {@link #sweepStale()} die Aufnahme.
 *
 * <p>Die Stuecke EINES MediaRecorder-Laufs ergeben aneinandergehaengt einen
 * gueltigen WebM-Strom (nur das erste Stueck traegt die Header). Deshalb ist die
 * Reihenfolge zwingend: Ein fehlendes Stueck wuerde die Datei zerstoeren, ein
 * Sprung wird darum abgelehnt und der Client sendet ab der erwarteten Nummer neu.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    /** Basisname der Rohdatei im Aufnahme-Verzeichnis (Endung je nach Browser-Format). */
    private static final String CAPTURE_BASENAME = "capture";

    /** Vom Browser gemeldete Formate und die passende Dateiendung fuer ffmpeg. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "video/webm", "webm",
            "audio/webm", "webm",
            "video/x-matroska", "mkv",
            "video/mp4", "mp4",
            "audio/mp4", "m4a",
            "audio/ogg", "ogg");

    private static final DateTimeFormatter TITLE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMAN);

    private final AppProperties props;
    private final SettingsService settings;
    private final RecordingRepo recordingRepo;
    private final RecordingService recordingService;

    /** Laufende Aufnahmen mit offener Datei; nach einem Neustart leer (siehe sweepStale). */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public CaptureService(AppProperties props, SettingsService settings,
                          RecordingRepo recordingRepo, RecordingService recordingService) {
        this.props = props;
        this.settings = settings;
        this.recordingRepo = recordingRepo;
        this.recordingService = recordingService;
    }

    /** Eine laufende Bildschirmaufnahme mit offenem Schreibkanal. */
    private static final class Session {
        final UUID ownerId;
        final Path file;
        final OutputStream out;
        final boolean processNow;
        int nextSeq;
        long bytes;
        Instant lastPersistedActivity;
        /** Nach einem Schreibfehler keine weiteren Stuecke annehmen - sonst wird der Strom unbrauchbar. */
        boolean broken;

        Session(UUID ownerId, Path file, OutputStream out, boolean processNow) {
            this.ownerId = ownerId;
            this.file = file;
            this.out = out;
            this.processNow = processNow;
            this.lastPersistedActivity = Instant.now();
        }
    }

    /** Rahmenbedingungen fuer das Frontend. */
    public record CaptureConfig(boolean enabled, long maxBytes, boolean diarizeAllowed) {}

    /** Sequenzluecke - der Client muss ab {@link #expectedSeq()} erneut senden. */
    public static class SequenceMismatchException extends RuntimeException {
        private final int expectedSeq;

        public SequenceMismatchException(int expectedSeq) {
            super("Unerwartete Reihenfolge - erwartet wird Stueck " + expectedSeq);
            this.expectedSeq = expectedSeq;
        }

        public int expectedSeq() { return expectedSeq; }
    }

    /** Die Aufnahme hat die konfigurierte Groessengrenze erreicht. */
    public static class CaptureTooLargeException extends RuntimeException {
        public CaptureTooLargeException(String message) { super(message); }
    }

    public CaptureConfig config() {
        return new CaptureConfig(
                settings.getBool(SettingsService.CAPTURE_ENABLED),
                maxBytes(),
                settings.getBool(SettingsService.WHISPER_DIARIZE));
    }

    private long maxBytes() {
        return settings.getLong(SettingsService.CAPTURE_MAX_MEGABYTES) * 1024L * 1024L;
    }

    /**
     * Legt eine neue Bildschirmaufnahme an und oeffnet die Rohdatei zum Anhaengen.
     *
     * @param mimeType vom Browser gewaehltes Aufnahmeformat (bestimmt die Dateiendung)
     * @param video    ob ein Bildschirm mitlaeuft (nur Ton = false); bestimmt die
     *                 Anzeige waehrend der Aufnahme, beim Abschluss wird das
     *                 tatsaechliche Format geprueft
     */
    public Recording start(UUID ownerId, String title, boolean aiAnalysis, boolean processNow,
                           boolean diarize, boolean video, String mimeType) throws IOException {
        if (!settings.getBool(SettingsService.CAPTURE_ENABLED)) {
            throw new IllegalStateException("Bildschirmaufnahme ist nicht freigeschaltet");
        }
        Recording recording = Recording.start(null, ownerId, null, "", video, aiAnalysis, diarize);
        recording.setSource(Recording.Source.CAPTURE);
        recording.setStatus(Recording.Status.RECORDING);
        recording.setCaptureLastChunkAt(Instant.now());
        recording.setTitle(title == null || title.isBlank()
                ? "Bildschirmaufnahme " + LocalDateTime.now().format(TITLE_TIME)
                : title.trim());

        Path dir = Path.of(props.getStorage().getRootDir()).resolve(recording.getId().toString());
        Files.createDirectories(dir);
        recording.setDirectory(dir.toAbsolutePath().toString());
        recordingRepo.save(recording);

        Path file = dir.resolve(CAPTURE_BASENAME + "." + extensionFor(mimeType));
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND), 1 << 16);
        sessions.put(recording.getId(), new Session(ownerId, file, out, processNow));

        log.info("Bildschirmaufnahme {} gestartet (Nutzer {}, Format {}, Video={}, Analyse={}, Diarisierung={})",
                recording.getId(), ownerId, mimeType, video, aiAnalysis, diarize);
        return recording;
    }

    /**
     * Haengt ein Stueck der laufenden Aufnahme an. Wiederholt gesendete Stuecke
     * (Netz-Retry nach erfolgreichem, aber verlorenem Response) werden verworfen,
     * Luecken abgelehnt.
     *
     * @return die als naechstes erwartete Sequenznummer
     */
    public int append(UUID recordingId, UUID ownerId, int seq, InputStream data) throws IOException {
        Session session = sessions.get(recordingId);
        if (session == null) {
            throw new IllegalStateException("Aufnahme ist nicht (mehr) aktiv");
        }
        if (!session.ownerId.equals(ownerId)) {
            throw new SecurityException("Aufnahme gehoert einem anderen Nutzer");
        }
        synchronized (session) {
            if (session.broken) {
                throw new IllegalStateException("Aufnahme wurde nach einem Schreibfehler beendet");
            }
            if (seq < session.nextSeq) {
                // Doppelt gesendetes Stueck - bereits geschrieben, nichts zu tun.
                log.debug("Bildschirmaufnahme {}: Stueck {} erneut empfangen, verworfen", recordingId, seq);
                return session.nextSeq;
            }
            if (seq > session.nextSeq) {
                throw new SequenceMismatchException(session.nextSeq);
            }
            long written;
            try {
                written = data.transferTo(session.out);
                session.out.flush();
            } catch (IOException e) {
                // Ein halb geschriebenes Stueck laesst sich nicht zurueckrollen; ein
                // erneuter Versuch wuerde die Bytes doppelt anhaengen. Also Schluss
                // machen - was bereits auf der Platte liegt, bleibt auswertbar.
                session.broken = true;
                log.error("Bildschirmaufnahme {}: Schreibfehler bei Stueck {} - Aufnahme wird beendet: {}",
                        recordingId, seq, e.getMessage());
                throw e;
            }
            session.bytes += written;
            session.nextSeq++;

            long limit = maxBytes();
            if (session.bytes > limit) {
                log.warn("Bildschirmaufnahme {} hat die Groessengrenze erreicht ({} > {} Bytes)",
                        recordingId, session.bytes, limit);
                throw new CaptureTooLargeException(
                        "Die Aufnahme hat die zulaessige Groesse erreicht und wird beendet");
            }
            touch(recordingId, session);
            return session.nextSeq;
        }
    }

    /**
     * Lebenszeichen einer laufenden Aufnahme (z.B. waehrend einer Pause, in der
     * keine Daten anfallen) - verhindert, dass der Sweeper sie fuer abgebrochen haelt.
     */
    public void heartbeat(UUID recordingId, UUID ownerId) {
        Session session = sessions.get(recordingId);
        if (session == null) {
            throw new IllegalStateException("Aufnahme ist nicht (mehr) aktiv");
        }
        if (!session.ownerId.equals(ownerId)) {
            throw new SecurityException("Aufnahme gehoert einem anderen Nutzer");
        }
        touch(recordingId, session);
    }

    /**
     * Aktivitaetszeitstempel fortschreiben. Bewusst hoechstens alle 30 s in die
     * Datenbank - bei 5-Sekunden-Stuecken waere sonst jede Aufnahme ein
     * Dauerschreiber, und fuer die Abbrucherkennung reicht die Genauigkeit.
     */
    private void touch(UUID recordingId, Session session) {
        Instant now = Instant.now();
        if (session.lastPersistedActivity.isAfter(now.minusSeconds(30))) return;
        session.lastPersistedActivity = now;
        recordingRepo.findById(recordingId).ifPresent(r -> {
            r.setCaptureLastChunkAt(now);
            recordingRepo.save(r);
        });
    }

    /** Beendet die Aufnahme regulaer und uebergibt sie an die Verarbeitung. */
    public Recording stop(UUID recordingId, UUID ownerId) {
        Session session = sessions.get(recordingId);
        if (session == null) {
            throw new IllegalStateException("Aufnahme ist nicht (mehr) aktiv");
        }
        if (!session.ownerId.equals(ownerId)) {
            throw new SecurityException("Aufnahme gehoert einem anderen Nutzer");
        }
        sessions.remove(recordingId);
        closeQuietly(session);
        log.info("Bildschirmaufnahme {} beendet ({} Stueck(e), {} Bytes)",
                recordingId, session.nextSeq, session.bytes);
        return finish(recordingId, session.file, session.processNow);
    }

    /** Bricht die Aufnahme ab und loescht alle Daten. */
    public void abort(UUID recordingId, UUID ownerId) {
        Session session = sessions.remove(recordingId);
        if (session != null) {
            if (!session.ownerId.equals(ownerId)) {
                sessions.put(recordingId, session);
                throw new SecurityException("Aufnahme gehoert einem anderen Nutzer");
            }
            closeQuietly(session);
        }
        Recording recording = recordingRepo.findById(recordingId).orElse(null);
        if (recording == null) return;
        if (!recording.getOwnerId().equals(ownerId)) {
            throw new SecurityException("Aufnahme gehoert einem anderen Nutzer");
        }
        deleteDirectoryQuietly(Path.of(recording.getDirectory()));
        recordingRepo.delete(recording);
        log.info("Bildschirmaufnahme {} abgebrochen und verworfen", recordingId);
    }

    /** Laeuft fuer diese Aufnahme gerade eine Uebertragung? */
    public boolean isActive(UUID recordingId) {
        return sessions.containsKey(recordingId);
    }

    /**
     * Rettet abgebrochene Bildschirmaufnahmen: Wenn der Browser weg ist (Tab
     * geschlossen, Rechner zugeklappt, Netz weg), kommen keine Stuecke mehr - die
     * bereits uebertragenen Daten sind aber gueltig und werden ganz normal
     * ausgewertet. Faengt auch die Aufnahmen ab, deren Sitzung ein Backend-Neustart
     * verloren hat.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweepStale() {
        long staleMinutes;
        try {
            staleMinutes = settings.getLong(SettingsService.CAPTURE_STALE_MINUTES);
        } catch (RuntimeException e) {
            log.warn("Einstellung {} unlesbar: {}", SettingsService.CAPTURE_STALE_MINUTES, e.getMessage());
            return;
        }
        Instant cutoff = Instant.now().minus(staleMinutes, ChronoUnit.MINUTES);
        for (Recording recording : recordingRepo.findBySourceAndStatus(
                Recording.Source.CAPTURE, Recording.Status.RECORDING)) {
            Instant last = recording.getCaptureLastChunkAt() != null
                    ? recording.getCaptureLastChunkAt() : recording.getStartedAt();
            if (last.isAfter(cutoff)) continue;

            UUID id = recording.getId();
            Session session = sessions.remove(id);
            if (session != null) closeQuietly(session);
            Path file = session != null ? session.file : findCaptureFile(recording);
            log.warn("Bildschirmaufnahme {} seit {} ohne Daten - wird abgeschlossen (gerettet)", id, last);
            try {
                finish(id, file, false);
            } catch (RuntimeException e) {
                log.error("Abgebrochene Bildschirmaufnahme {} konnte nicht abgeschlossen werden: {}",
                        id, e.getMessage());
            }
        }
    }

    /**
     * Gemeinsamer Abschluss (regulaeres Stoppen wie Rettung): Rohdatei pruefen,
     * Video-Anteil erkennen und in die Upload-Verarbeitungsstrecke geben.
     */
    private Recording finish(UUID recordingId, Path file, boolean processNow) {
        Recording recording = recordingRepo.findById(recordingId).orElse(null);
        if (recording == null) {
            throw new IllegalStateException("Aufnahme nicht gefunden");
        }
        recording.setEndedAt(Instant.now());
        recording.setCaptureLastChunkAt(null);

        long size = fileSize(file);
        long minBytes = settings.getLong(SettingsService.RECORDING_MIN_AUDIO_BYTES);
        if (size < minBytes) {
            recording.setStatus(Recording.Status.FAILED);
            recording.setVideoStatus(Recording.VideoStatus.NONE);
            recording.setDiscardReason(
                    "Es wurden keine verwertbaren Aufnahmedaten empfangen (%d Bytes)".formatted(size));
            recordingRepo.save(recording);
            log.warn("Bildschirmaufnahme {} ohne verwertbare Daten ({} Bytes)", recordingId, size);
            return recording;
        }

        boolean hasVideo = recordingService.hasVideoStream(file);
        recording.setRecordVideo(hasVideo);
        recording.setVideoStatus(hasVideo ? Recording.VideoStatus.MUXING : Recording.VideoStatus.NONE);
        recording.setStatus(Recording.Status.FINALIZING);
        recordingRepo.save(recording);

        recordingService.processSourceFile(recordingId, file, hasVideo, processNow);
        log.info("Bildschirmaufnahme {} an die Verarbeitung uebergeben ({} Bytes, Video={})",
                recordingId, size, hasVideo);
        return recording;
    }

    /** Rohdatei einer Aufnahme suchen, deren Sitzung nicht mehr im Speicher liegt. */
    private Path findCaptureFile(Recording recording) {
        Path dir = Path.of(recording.getDirectory());
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(CAPTURE_BASENAME + "."))
                    .findFirst()
                    .orElse(dir.resolve(CAPTURE_BASENAME + ".webm"));
        } catch (IOException e) {
            return dir.resolve(CAPTURE_BASENAME + ".webm");
        }
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) return "webm";
        String base = mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        String ext = EXTENSIONS.get(base);
        if (ext == null) {
            log.warn("Unbekanntes Aufnahmeformat '{}' - wird als WebM abgelegt", mimeType);
            return "webm";
        }
        return ext;
    }

    private static long fileSize(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void closeQuietly(Session session) {
        try {
            session.out.close();
        } catch (IOException e) {
            log.warn("Aufnahmedatei {} konnte nicht sauber geschlossen werden: {}",
                    session.file, e.getMessage());
        }
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Verzeichnis {} konnte nicht geloescht werden: {}", dir, e.getMessage());
        }
    }

    /** Beim Herunterfahren offene Dateien schliessen, damit nichts im Puffer verloren geht. */
    @PreDestroy
    void shutdown() {
        List<UUID> open = List.copyOf(sessions.keySet());
        for (UUID id : open) {
            Session session = sessions.remove(id);
            if (session == null) continue;
            closeQuietly(session);
            log.info("Bildschirmaufnahme {} beim Herunterfahren gesichert ({} Bytes) - "
                    + "wird nach dem Start abgeschlossen", id, session.bytes);
        }
    }

    /** Nur fuer Tests: Anzahl offener Aufnahmen. */
    int openSessions() {
        return sessions.size();
    }
}
