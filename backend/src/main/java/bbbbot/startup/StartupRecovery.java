package bbbbot.startup;

import bbbbot.config.AppProperties;
import bbbbot.docs.RecordingDocumentService;
import bbbbot.domain.BotSession;
import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingDocument;
import bbbbot.domain.RecordingSegment;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingDocumentRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Bereinigt beim Start Zustaende, die einen vorherigen Backend-Lauf nicht
 * ueberlebt haben. Nach einem Absturz/Neustart existieren keine Bot-Instanzen,
 * Transkodierungs- oder Verarbeitungs-Threads mehr - die zugehoerigen DB-Zeilen
 * stehen aber noch auf "laeuft" und wuerden sonst ewig haengen bleiben.
 *
 * <ul>
 *   <li>Aktive Bot-Sessions (STARTING/JOINED/RECORDING/RECONNECTING) -> FAILED</li>
 *   <li>Aufnahmen in RECORDING/FINALIZING -> FAILED (samt haengender Segmente)</li>
 *   <li>Verarbeitungs-Jobs in RUNNING -> PENDING (werden erneut aufgegriffen);
 *       zugehoerige Aufnahmen in PROCESSING -> RECORDED</li>
 *   <li>Videos in RECORDING/MUXING -> READY, falls die fertige MP4 schon auf der
 *       Platte liegt, sonst FAILED - unabhaengig vom Status der Aufnahme</li>
 *   <li>Unterlagen mit unterbrochener Textextraktion (PENDING) -> neu eingereiht</li>
 *   <li>verwaiste Video-Temp-Verzeichnisse werden entfernt</li>
 * </ul>
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private static final String INTERRUPTED = "Beim Backend-Neustart unterbrochen";

    private final BotSessionRepo sessionRepo;
    private final RecordingRepo recordingRepo;
    private final RecordingSegmentRepo segmentRepo;
    private final ProcessingJobRepo jobRepo;
    private final RecordingDocumentRepo documentRepo;
    private final RecordingDocumentService documentService;
    private final AppProperties props;

    public StartupRecovery(BotSessionRepo sessionRepo, RecordingRepo recordingRepo,
                           RecordingSegmentRepo segmentRepo, ProcessingJobRepo jobRepo,
                           RecordingDocumentRepo documentRepo,
                           RecordingDocumentService documentService,
                           AppProperties props) {
        this.sessionRepo = sessionRepo;
        this.recordingRepo = recordingRepo;
        this.segmentRepo = segmentRepo;
        this.jobRepo = jobRepo;
        this.documentRepo = documentRepo;
        this.documentService = documentService;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        recoverBotSessions();
        recoverRecordings();
        recoverVideos();
        recoverJobs();
        recoverDocuments();
        sweepVideoTmp();
    }

    /**
     * Unterlagen, deren Textextraktion der Neustart unterbrochen hat, werden neu
     * eingereiht statt auf FAILED gesetzt: Die Datei liegt vollstaendig auf der
     * Platte, ein zweiter Lauf kostet nur Rechenzeit - und ein Nutzer, der eine
     * Unterlage hochgeladen hat, soll sie nicht von Hand erneut anstossen muessen.
     */
    private void recoverDocuments() {
        List<RecordingDocument> pending = documentRepo.findByStatus(RecordingDocument.Status.PENDING);
        for (RecordingDocument document : pending) {
            documentService.retry(document);
        }
        if (!pending.isEmpty()) {
            log.info("Recovery: {} Unterlage(n) zur Textextraktion neu eingereiht", pending.size());
        }
    }

    private void recoverBotSessions() {
        List<BotSession> stuck = sessionRepo.findByStatusIn(List.of(
                BotSession.Status.STARTING, BotSession.Status.JOINED,
                BotSession.Status.RECORDING, BotSession.Status.RECONNECTING));
        for (BotSession s : stuck) {
            s.setStatus(BotSession.Status.FAILED);
            s.setLastError(INTERRUPTED);
            if (s.getEndedAt() == null) s.setEndedAt(Instant.now());
            sessionRepo.save(s);
        }
        if (!stuck.isEmpty()) log.info("Recovery: {} verwaiste Bot-Session(s) auf FAILED gesetzt", stuck.size());
    }

    private void recoverRecordings() {
        List<Recording> stuck = recordingRepo.findByStatusIn(List.of(
                Recording.Status.RECORDING, Recording.Status.FINALIZING)).stream()
                // Laufende Bildschirmaufnahmen NICHT verwerfen: Ihre Rohdaten liegen
                // vollstaendig bis zum letzten uebertragenen Stueck auf der Platte.
                // CaptureService.sweepStale() schliesst sie kurz nach dem Start ab
                // und rettet damit den Inhalt.
                .filter(r -> !(r.getSource() == Recording.Source.CAPTURE
                        && r.getStatus() == Recording.Status.RECORDING))
                .toList();
        for (Recording r : stuck) {
            r.setStatus(Recording.Status.FAILED);
            r.setDiscardReason(INTERRUPTED);
            if (r.getEndedAt() == null) r.setEndedAt(Instant.now());
            if (r.getVideoStatus() == Recording.VideoStatus.RECORDING
                    || r.getVideoStatus() == Recording.VideoStatus.MUXING) {
                r.setVideoStatus(Recording.VideoStatus.FAILED);
            }
            recordingRepo.save(r);
            for (RecordingSegment seg : segmentRepo.findByRecordingIdOrderBySeq(r.getId())) {
                if (seg.getStatus() == RecordingSegment.Status.RECORDING
                        || seg.getStatus() == RecordingSegment.Status.TRANSCODING) {
                    seg.setStatus(RecordingSegment.Status.FAILED);
                    seg.setError(INTERRUPTED);
                    segmentRepo.save(seg);
                }
            }
        }
        if (!stuck.isEmpty()) log.info("Recovery: {} haengende Aufnahme(n) auf FAILED gesetzt", stuck.size());
    }

    /**
     * Repariert haengende Video-Zustaende. Der Mux-Pool ist rein im Speicher:
     * Was beim Neustart lief oder in der Warteschlange stand, ist weg - die
     * Aufnahme steht aber weiter auf RECORDING/MUXING und das Frontend zeigt
     * dauerhaft "wird verarbeitet". {@link #recoverRecordings()} greift dafuer
     * nicht, weil es nur Aufnahmen behandelt, die selbst noch auf RECORDING/
     * FINALIZING stehen - eine laengst fertig ausgewertete Aufnahme mit
     * haengendem Video bliebe ewig stehen.
     *
     * <p>Ist die MP4 schon fertig geschrieben, wird sie nachtraeglich eingehaengt
     * statt verworfen; sonst wird ehrlich FAILED angezeigt.
     */
    private void recoverVideos() {
        List<Recording> stuck = recordingRepo.findByVideoStatusIn(List.of(
                Recording.VideoStatus.RECORDING, Recording.VideoStatus.MUXING));
        int rescued = 0;
        int failed = 0;
        for (Recording r : stuck) {
            // Laufende Bildschirmaufnahmen werden von CaptureService.sweepStale()
            // regulaer abgeschlossen und bekommen ihr Video danach noch.
            if (r.getSource() == Recording.Source.CAPTURE
                    && r.getStatus() == Recording.Status.RECORDING) continue;

            Path mp4 = Path.of(r.getDirectory()).resolve("meeting.mp4");
            if (Files.isRegularFile(mp4) && sizeQuietly(mp4) > 0) {
                r.setVideoPath(mp4.toAbsolutePath().toString());
                r.setVideoStatus(Recording.VideoStatus.READY);
                rescued++;
            } else {
                r.setVideoStatus(Recording.VideoStatus.FAILED);
                failed++;
            }
            recordingRepo.save(r);
        }
        if (rescued > 0 || failed > 0) {
            log.info("Recovery: {} fertige(s) Video(s) nachtraeglich eingehaengt, "
                    + "{} haengende(s) Video(s) auf FAILED gesetzt", rescued, failed);
        }
    }

    private static long sizeQuietly(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private void recoverJobs() {
        List<ProcessingJob> running = jobRepo.findByStatusOrderByCreatedAt(ProcessingJob.Status.RUNNING);
        for (ProcessingJob job : running) {
            job.setStatus(ProcessingJob.Status.PENDING);
            job.setStartedAt(null);
            job.setLastError(INTERRUPTED + " - wird erneut versucht");
            jobRepo.save(job);
            recordingRepo.findById(job.getRecordingId()).ifPresent(r -> {
                if (r.getStatus() == Recording.Status.PROCESSING) {
                    r.setStatus(Recording.Status.RECORDED);
                    recordingRepo.save(r);
                }
            });
        }
        if (!running.isEmpty()) log.info("Recovery: {} laufende(n) Verarbeitungs-Job(s) neu eingereiht", running.size());
    }

    /** Alle Video-Temp-Verzeichnisse sind nach einem Neustart verwaist (kein Bot mehr aktiv). */
    private void sweepVideoTmp() {
        Path root = Path.of(props.getStorage().getRootDir()).resolve("video-tmp");
        if (!Files.isDirectory(root)) return;
        int removed = 0;
        try (var children = Files.list(root)) {
            for (Path dir : children.toList()) {
                if (deleteRecursivelyQuietly(dir)) removed++;
            }
        } catch (IOException e) {
            log.warn("Video-Temp-Verzeichnis konnte nicht durchsucht werden: {}", e.getMessage());
        }
        if (removed > 0) log.info("Recovery: {} verwaiste Video-Temp-Verzeichnis(se) entfernt", removed);
    }

    private boolean deleteRecursivelyQuietly(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
            return true;
        } catch (IOException e) {
            log.warn("Video-Temp {} konnte nicht geloescht werden: {}", path, e.getMessage());
            return false;
        }
    }
}
