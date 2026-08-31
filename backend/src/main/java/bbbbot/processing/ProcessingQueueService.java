package bbbbot.processing;

import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Betriebssicht auf die Verarbeitungs-Warteschlange (Issue #5).
 *
 * <p>Die Aufgabe ist eine Frage, die sich morgens stellt: Ist die Nacht
 * durchgelaufen? Bisher liess sie sich nur beantworten, indem man einzelne
 * Aufnahmen durchklickt oder das Server-Log liest. Diese Klasse sammelt, was
 * dafuer noetig ist - was wartet, was laeuft, was ist gescheitert und woran,
 * wie lange die Schritte dauern, und ob das Zeitfenster gerade offen ist.
 *
 * <p>Bewusst getrennt von {@link ProcessingService}: Der fuehrt die Auftraege
 * aus, dieser hier schaut nur zu. Nur das erneute Anstossen greift ein - und
 * das aendert lediglich den Zustand des Auftrags, ausgefuehrt wird er wie jeder
 * andere vom Scheduler.
 */
@Service
public class ProcessingQueueService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingQueueService.class);

    private final ProcessingJobRepo jobRepo;
    private final RecordingRepo recordingRepo;
    private final SettingsService settings;

    public ProcessingQueueService(ProcessingJobRepo jobRepo, RecordingRepo recordingRepo,
                                  SettingsService settings) {
        this.jobRepo = jobRepo;
        this.recordingRepo = recordingRepo;
        this.settings = settings;
    }

    /** Vollstaendiges Bild fuer den Admin-Tab "Verarbeitung". */
    public Overview overview() {
        boolean windowOpen = isWindowOpen();
        List<ProcessingJob> queue = jobRepo.findByStatusInOrderByCreatedAtAsc(
                List.of(ProcessingJob.Status.PENDING, ProcessingJob.Status.RUNNING));
        List<ProcessingJob> failures = jobRepo.findTop20ByStatusOrderByFinishedAtDesc(
                ProcessingJob.Status.FAILED);
        List<ProcessingJob> recentDone = jobRepo.findTop50ByStatusOrderByFinishedAtDesc(
                ProcessingJob.Status.DONE);

        Instant now = Instant.now();
        return new Overview(
                windowOpen,
                settings.get(SettingsService.PROCESSING_WINDOW_START),
                settings.get(SettingsService.PROCESSING_WINDOW_END),
                jobRepo.countByStatus(ProcessingJob.Status.PENDING),
                jobRepo.countByStatus(ProcessingJob.Status.RUNNING),
                jobRepo.countByStatus(ProcessingJob.Status.FAILED),
                jobRepo.countByStatus(ProcessingJob.Status.DONE),
                describe(queue, windowOpen, now),
                describe(failures, windowOpen, now),
                durations(recentDone));
    }

    /** Laeuft die Verarbeitung gerade, oder wartet sie auf das Zeitfenster? */
    public boolean isWindowOpen() {
        try {
            LocalTime start = LocalTime.parse(settings.get(SettingsService.PROCESSING_WINDOW_START));
            LocalTime end = LocalTime.parse(settings.get(SettingsService.PROCESSING_WINDOW_END));
            return ProcessingWindow.isWithinWindow(LocalTime.now(), start, end);
        } catch (RuntimeException e) {
            // Unlesbare Einstellung: Der Scheduler wuerde daran ebenfalls
            // scheitern - das gehoert in die Uebersicht, nicht in einen 500er.
            log.warn("Zeitfenster der Verarbeitung ist nicht lesbar: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Einen gescheiterten Auftrag erneut anstossen.
     *
     * <p>Der Versuchszaehler wird zurueckgesetzt - sonst waere ein Auftrag, der
     * seine drei Versuche verbraucht hat, dauerhaft blockiert, und genau der ist
     * der Grund, hier einen Knopf zu haben.
     *
     * <p>Der Auftrag wird dabei auf "sofort" gesetzt: Wer morgens auf "erneut
     * versuchen" drueckt, hat die Ursache behoben und will das Ergebnis heute -
     * nicht in der naechsten Nacht. Das ist dieselbe Bedeutung wie bei allen
     * von Hand ausgeloesten Aktionen dieser Anwendung.
     *
     * @throws IllegalArgumentException wenn es den Auftrag nicht gibt
     * @throws IllegalStateException    wenn er nicht gescheitert ist
     */
    public ProcessingJob retry(UUID jobId) {
        ProcessingJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Auftrag nicht gefunden"));
        if (job.getStatus() != ProcessingJob.Status.FAILED) {
            throw new IllegalStateException(
                    "Nur gescheiterte Auftraege lassen sich erneut anstossen (Status: "
                            + job.getStatus() + ")");
        }
        if (recordingRepo.findById(job.getRecordingId()).isEmpty()) {
            throw new IllegalStateException("Die Aufnahme zu diesem Auftrag existiert nicht mehr");
        }
        job.setStatus(ProcessingJob.Status.PENDING);
        job.setAttempts(0);
        job.setImmediate(true);
        job.setLastError(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        job.setSttMs(null);
        job.setCorrectionMs(null);
        job.setSummaryMs(null);
        jobRepo.save(job);
        log.info("Auftrag {} wurde von Hand erneut angestossen (Aufnahme {})",
                job.getId(), job.getRecordingId());
        return job;
    }

    // ------------------------------------------------------------ Aufbereitung

    /**
     * Auftraege um die Angaben der Aufnahme ergaenzen. Titel und Status kommen in
     * EINER Abfrage dazu und nicht pro Zeile - die Uebersicht laedt sich im
     * Sekundentakt nach, solange der Tab offen ist.
     */
    private List<JobDetail> describe(List<ProcessingJob> jobs, boolean windowOpen, Instant now) {
        if (jobs.isEmpty()) return List.of();
        List<UUID> recordingIds = jobs.stream().map(ProcessingJob::getRecordingId).distinct().toList();
        Map<UUID, Recording> recordings = recordingRepo.findAllById(recordingIds).stream()
                .collect(Collectors.toMap(Recording::getId, Function.identity()));

        List<JobDetail> details = new ArrayList<>(jobs.size());
        for (ProcessingJob job : jobs) {
            Recording recording = recordings.get(job.getRecordingId());
            details.add(new JobDetail(job, recording, waitingMs(job, now), waitsForWindow(job, windowOpen)));
        }
        return details;
    }

    /**
     * Wie lange dieser Auftrag schon wartet. Fuer wartende Auftraege bis jetzt,
     * fuer laufende bis zum Start - danach ist es Laufzeit, nicht Wartezeit.
     */
    private static Long waitingMs(ProcessingJob job, Instant now) {
        Instant until = switch (job.getStatus()) {
            case PENDING -> now;
            case RUNNING -> job.getStartedAt();
            default -> job.getStartedAt() == null ? job.getFinishedAt() : job.getStartedAt();
        };
        if (until == null || job.getCreatedAt() == null) return null;
        return Math.max(0, Duration.between(job.getCreatedAt(), until).toMillis());
    }

    /**
     * Steht dieser Auftrag nur deshalb, weil das Zeitfenster zu ist? Beantwortet
     * die haeufigste Rueckfrage: "Warum laeuft nichts?"
     */
    private static boolean waitsForWindow(ProcessingJob job, boolean windowOpen) {
        return job.getStatus() == ProcessingJob.Status.PENDING && !job.isImmediate() && !windowOpen;
    }

    /**
     * Dauer-Kennzahlen der letzten fertigen Auftraege. Median statt Mittelwert:
     * Eine einzelne dreistuendige Aufnahme soll das Bild nicht verschieben. Das
     * Maximum steht daneben, weil genau der Ausreisser interessiert.
     */
    private static Durations durations(List<ProcessingJob> done) {
        List<Long> total = done.stream().map(ProcessingJob::durationMs).filter(java.util.Objects::nonNull).toList();
        return new Durations(
                total.size(),
                median(total), max(total),
                median(values(done, ProcessingJob::getSttMs)),
                median(values(done, ProcessingJob::getCorrectionMs)),
                median(values(done, ProcessingJob::getSummaryMs)));
    }

    private static List<Long> values(List<ProcessingJob> jobs, Function<ProcessingJob, Long> field) {
        return jobs.stream().map(field).filter(java.util.Objects::nonNull).toList();
    }

    private static Long median(List<Long> values) {
        if (values.isEmpty()) return null;
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(mid)
                : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    private static Long max(List<Long> values) {
        return values.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    // ------------------------------------------------------------ Ergebnistypen

    /**
     * Ein Auftrag mit dem, was die Uebersicht dazu braucht. Die Aufnahme kann
     * fehlen (gelöscht, waehrend der Auftrag noch in der Liste steht).
     */
    public record JobDetail(ProcessingJob job, Recording recording,
                            Long waitingMs, boolean waitsForWindow) {}

    public record Durations(int sample, Long medianMs, Long maxMs,
                            Long medianSttMs, Long medianCorrectionMs, Long medianSummaryMs) {}

    public record Overview(boolean windowOpen, String windowStart, String windowEnd,
                           long pending, long running, long failed, long done,
                           List<JobDetail> queue, List<JobDetail> failures,
                           Durations durations) {}
}
