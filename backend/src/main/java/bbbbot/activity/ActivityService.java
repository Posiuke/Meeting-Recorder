package bbbbot.activity;

import bbbbot.bot.BotManager;
import bbbbot.docs.RecordingDocumentService;
import bbbbot.domain.ProcessingJob;
import bbbbot.processing.ProcessingWindow;
import bbbbot.processing.ProcessingService;
import bbbbot.recording.CaptureService;
import bbbbot.recording.RecordingService;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.settings.SettingsService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wie beschaeftigt ist der Dienst? Grundlage, um ihn bei Leerlauf
 * herunterzufahren (Scale-to-zero) - ohne laufende Arbeit abzuwuergen.
 *
 * <p>Bewusst nicht ueber Netzwerkverkehr: Eine Auswertung laeuft minutenlang
 * ohne jede Nutzeranfrage, eine Bildschirmaufnahme dagegen schickt staendig
 * Daten. Massgeblich sind deshalb die tatsaechlich laufenden Arbeiten plus die
 * Zeit seit der letzten Nutzeranfrage.
 */
@Service
public class ActivityService {

    private final Instant startedAt = Instant.now();
    private final AtomicReference<Instant> lastRequestAt = new AtomicReference<>(startedAt);

    private final ProcessingService processing;
    private final ProcessingJobRepo jobRepo;
    private final CaptureService captures;
    private final BotManager bots;
    private final RecordingService recordings;
    private final RecordingDocumentService documents;
    private final SettingsService settings;

    public ActivityService(ProcessingService processing, ProcessingJobRepo jobRepo, CaptureService captures,
                           BotManager bots, RecordingService recordings, RecordingDocumentService documents,
                           SettingsService settings) {
        this.processing = processing;
        this.jobRepo = jobRepo;
        this.captures = captures;
        this.bots = bots;
        this.recordings = recordings;
        this.documents = documents;
        this.settings = settings;
    }

    /** Von {@link ActivityTrackingFilter} bei jeder Nutzeranfrage aufgerufen. */
    public void touch() {
        lastRequestAt.set(Instant.now());
    }

    /**
     * @param busy          laeuft gerade Arbeit, die ein Herunterfahren abbrechen wuerde
     * @param idleSeconds   Sekunden seit der letzten Nutzeranfrage
     * @param work          Zaehler je Art (nur Werte > 0 zaehlen als beschaeftigt)
     * @param deferredJobs  Auswertungen, die auf das Nachtfenster warten
     * @param nextWindowStart Beginn des naechsten Nachtfensters, falls Auswertungen warten -
     *                      so kann der Betrieb den Dienst dafuer gezielt wecken
     */
    public record Report(boolean busy, long idleSeconds, Instant lastRequestAt, Instant startedAt,
                         Map<String, Integer> work, List<String> reasons, int deferredJobs,
                         Instant nextWindowStart) {}

    public Report report() {
        LocalTime windowStart = LocalTime.parse(settings.get(SettingsService.PROCESSING_WINDOW_START));
        LocalTime windowEnd = LocalTime.parse(settings.get(SettingsService.PROCESSING_WINDOW_END));
        boolean windowOpen = ProcessingWindow.isWithinWindow(LocalTime.now(), windowStart, windowEnd);

        List<ProcessingJob> pending = jobRepo.findByStatusOrderByCreatedAt(ProcessingJob.Status.PENDING);
        int runnableNow = (int) pending.stream().filter(j -> j.isImmediate() || windowOpen).count();
        int deferred = pending.size() - runnableNow;

        Map<String, Integer> work = new LinkedHashMap<>();
        work.put("processing", processing.isBusy() ? 1 : 0);
        work.put("pendingJobs", runnableNow);
        work.put("captures", captures.openSessions());
        work.put("bots", bots.listActive().size());
        work.put("media", recordings.activeMediaWork());
        work.put("documents", documents.pendingExtractions());

        List<String> reasons = new ArrayList<>();
        work.forEach((k, v) -> {
            if (v > 0) reasons.add(k);
        });
        Instant last = lastRequestAt.get();
        long idle = Math.max(0, Duration.between(last, Instant.now()).toSeconds());
        Instant next = deferred > 0 ? nextOccurrence(windowStart) : null;
        return new Report(!reasons.isEmpty(), idle, last, startedAt, work, reasons, deferred, next);
    }

    /** Naechster Zeitpunkt (Serverzeitzone), zu dem die Uhr {@code time} zeigt. */
    static Instant nextOccurrence(LocalTime time) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime candidate = LocalDateTime.of(LocalDate.now(zone), time);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return candidate.atZone(zone).toInstant();
    }
}
