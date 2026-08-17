package bbbbot.processing;

import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.llm.SummaryService;
import bbbbot.recording.ParticipantService;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import bbbbot.settings.SettingsService;
import bbbbot.stt.TranscriptAssembler;
import bbbbot.stt.WhisperClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Job-Queue fuer die nachgelagerte Verarbeitung (STT + Zusammenfassung).
 *
 * Ein Scheduler prueft regelmaessig auf offene Jobs. Normale Jobs laufen nur
 * im Admin-Zeitfenster (Ressourcenschonung tagsueber, GPU nachts frei),
 * "Sofort auswerten"-Jobs (immediate=true) immer. Es laeuft maximal ein Job
 * gleichzeitig - Whisper und das LLM teilen sich dieselbe GPU.
 */
@Service
public class ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);
    private static final int MAX_JOB_ATTEMPTS = 3;

    private final ProcessingJobRepo jobRepo;
    private final RecordingRepo recordingRepo;
    private final RecordingSegmentRepo segmentRepo;
    private final SummaryRepo summaryRepo;
    private final WhisperClient whisper;
    private final SummaryService summaryService;
    private final bbbbot.llm.TranscriptCorrectionService correctionService;
    private final ParticipantService participantService;
    private final SettingsService settings;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "processing");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public ProcessingService(ProcessingJobRepo jobRepo, RecordingRepo recordingRepo,
                             RecordingSegmentRepo segmentRepo, SummaryRepo summaryRepo,
                             WhisperClient whisper, SummaryService summaryService,
                             bbbbot.llm.TranscriptCorrectionService correctionService,
                             ParticipantService participantService, SettingsService settings) {
        this.jobRepo = jobRepo;
        this.recordingRepo = recordingRepo;
        this.segmentRepo = segmentRepo;
        this.summaryRepo = summaryRepo;
        this.whisper = whisper;
        this.summaryService = summaryService;
        this.correctionService = correctionService;
        this.participantService = participantService;
        this.settings = settings;
    }

    /** Manuell ausgeloeste Sofort-Auswertung aus dem Frontend. */
    public ProcessingJob enqueueImmediate(UUID recordingId) {
        return enqueueManual(recordingId, false);
    }

    /**
     * Schritt 1 der manuellen Zwei-Schritt-Auswertung: nur die Transkription
     * (Whisper) laeuft sofort, die KI-Zusammenfassung wird NICHT erstellt.
     * Die Aufnahme steht danach auf TRANSCRIBED; die Zusammenfassung wird
     * anschliessend separat ueber "Jetzt auswerten" (enqueueImmediate)
     * angestossen und ueberspringt dabei die vorhandenen Transkripte.
     */
    public ProcessingJob enqueueTranscribeOnly(UUID recordingId) {
        return enqueueManual(recordingId, true);
    }

    /**
     * Gemeinsame Logik der manuellen Auswertungs-Aktionen. Ein wartender
     * automatischer Job (Nachtfenster) wird auf die gewuenschte Sofort-Aktion
     * umgestellt. Ein bereits vom Nutzer angeforderter Sofort-Job darf dabei
     * hochgestuft (Nur-Transkription -> volle Auswertung), aber nie herabgestuft
     * werden - sonst entfiele eine explizit bestellte Zusammenfassung.
     */
    private ProcessingJob enqueueManual(UUID recordingId, boolean transcribeOnly) {
        Recording recording = recordingRepo.findById(recordingId)
                .orElseThrow(() -> new IllegalArgumentException("Aufnahme nicht gefunden"));
        if (recording.getStatus() == Recording.Status.RECORDING
                || recording.getStatus() == Recording.Status.FINALIZING) {
            throw new IllegalStateException("Aufnahme laeuft noch");
        }
        if (transcribeOnly && (recording.getStatus() == Recording.Status.DONE
                || recording.getStatus() == Recording.Status.DISCARDED)) {
            throw new IllegalStateException(
                    "Aufnahme ist bereits ausgewertet - bitte 'Transkription neu erstellen' verwenden");
        }
        boolean open = jobRepo.existsByRecordingIdAndStatusIn(recordingId,
                List.of(ProcessingJob.Status.PENDING, ProcessingJob.Status.RUNNING));
        if (open) {
            List<ProcessingJob> jobs = jobRepo.findByRecordingIdOrderByCreatedAtDesc(recordingId);
            for (ProcessingJob job : jobs) {
                if (job.getStatus() != ProcessingJob.Status.PENDING) continue;
                if (transcribeOnly && job.isImmediate() && !job.isTranscribeOnly()) {
                    // Kein Downgrade: Eine bereits angeforderte volle
                    // Sofort-Auswertung bleibt bestehen.
                    throw new IllegalStateException("Eine vollstaendige Auswertung ist bereits angefordert");
                }
                job.setImmediate(true);
                job.setTranscribeOnly(transcribeOnly);
                jobRepo.save(job);
                return job;
            }
            throw new IllegalStateException("Verarbeitung laeuft bereits");
        }
        ProcessingJob job = ProcessingJob.create(recordingId, true);
        job.setTranscribeOnly(transcribeOnly);
        jobRepo.save(job);
        return job;
    }

    /**
     * Erneute Auswertung einer bereits ausgewerteten Aufnahme: laeuft sofort;
     * nach erfolgreichem Abschluss werden die bisherigen Zusammenfassungen durch
     * die neue ersetzt. Vorhandene Transkripte werden wiederverwendet (kein
     * erneutes Whisper), nur die Zusammenfassung wird neu erstellt.
     */
    public ProcessingJob enqueueReprocess(UUID recordingId) {
        Recording recording = recordingRepo.findById(recordingId)
                .orElseThrow(() -> new IllegalArgumentException("Aufnahme nicht gefunden"));
        if (recording.getStatus() == Recording.Status.RECORDING
                || recording.getStatus() == Recording.Status.FINALIZING) {
            throw new IllegalStateException("Aufnahme laeuft noch");
        }
        if (jobRepo.existsByRecordingIdAndStatusIn(recordingId,
                List.of(ProcessingJob.Status.PENDING, ProcessingJob.Status.RUNNING))) {
            throw new IllegalStateException("Verarbeitung laeuft bereits");
        }
        if (summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recordingId).isEmpty()) {
            throw new IllegalStateException("Keine vorhandene Auswertung - bitte 'Jetzt auswerten' verwenden");
        }
        ProcessingJob job = ProcessingJob.create(recordingId, true);
        job.setReplaceExisting(true);
        jobRepo.save(job);
        return job;
    }

    /**
     * Erneute Transkription: Die Spracherkennung laeuft fuer ALLE Segmente neu
     * (z.B. nach einem Whisper-Konfigurationsfix oder aktivierter
     * Sprechererkennung), anschliessend wird auch die Zusammenfassung neu
     * erstellt. Vorhandene Zusammenfassungen werden erst nach Erfolg ersetzt.
     */
    public ProcessingJob enqueueRetranscribe(UUID recordingId) {
        Recording recording = recordingRepo.findById(recordingId)
                .orElseThrow(() -> new IllegalArgumentException("Aufnahme nicht gefunden"));
        if (recording.getStatus() == Recording.Status.RECORDING
                || recording.getStatus() == Recording.Status.FINALIZING) {
            throw new IllegalStateException("Aufnahme laeuft noch");
        }
        if (jobRepo.existsByRecordingIdAndStatusIn(recordingId,
                List.of(ProcessingJob.Status.PENDING, ProcessingJob.Status.RUNNING))) {
            throw new IllegalStateException("Verarbeitung laeuft bereits");
        }
        boolean hasAudio = segmentRepo.findByRecordingIdOrderBySeq(recordingId).stream()
                .anyMatch(s -> s.getStatus() == RecordingSegment.Status.READY);
        if (!hasAudio) {
            throw new IllegalStateException("Keine Audio-Segmente vorhanden - Transkription nicht moeglich");
        }
        ProcessingJob job = ProcessingJob.create(recordingId, true);
        job.setRedoTranscripts(true);
        // Nur FERTIGE Zusammenfassungen zaehlen: uebrig gebliebene FAILED-Zeilen
        // (z.B. aus einem gescheiterten Schritt 2) duerfen die Zwei-Schritt-
        // Auswertung nicht in eine volle Auswertung umwandeln.
        job.setReplaceExisting(summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recordingId).stream()
                .anyMatch(s -> s.getStatus() == bbbbot.domain.Summary.Status.DONE));
        // Mitten in der Zwei-Schritt-Auswertung (TRANSCRIBED, noch keine
        // Zusammenfassung) bleibt auch die erneute Transkription bei Schritt 1.
        job.setTranscribeOnly(recording.getStatus() == Recording.Status.TRANSCRIBED
                && !job.isReplaceExisting());
        jobRepo.save(job);
        return job;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void poll() {
        if (busy.get()) return;
        List<ProcessingJob> pending = jobRepo.findByStatusOrderByCreatedAt(ProcessingJob.Status.PENDING);
        if (pending.isEmpty()) return;

        LocalTime windowStart = LocalTime.parse(settings.get(SettingsService.PROCESSING_WINDOW_START));
        LocalTime windowEnd = LocalTime.parse(settings.get(SettingsService.PROCESSING_WINDOW_END));
        boolean windowOpen = ProcessingWindow.isWithinWindow(LocalTime.now(), windowStart, windowEnd);

        ProcessingJob next = pending.stream()
                .filter(j -> j.isImmediate() || windowOpen)
                .findFirst()
                .orElse(null);
        if (next == null) return;

        if (!busy.compareAndSet(false, true)) return;
        UUID jobId = next.getId();
        worker.execute(() -> {
            try {
                runJob(jobId);
            } finally {
                busy.set(false);
            }
        });
    }

    void runJob(UUID jobId) {
        ProcessingJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != ProcessingJob.Status.PENDING) return;
        job.setStatus(ProcessingJob.Status.RUNNING);
        job.setStartedAt(Instant.now());
        job.setAttempts(job.getAttempts() + 1);
        jobRepo.save(job);

        Recording recording = recordingRepo.findById(job.getRecordingId()).orElse(null);
        if (recording == null) {
            finishJob(job, false, "Aufnahme existiert nicht mehr");
            return;
        }
        saveStatus(recording.getId(), Recording.Status.PROCESSING);
        log.info("Starte Verarbeitung fuer Aufnahme {} (Job {}, Versuch {})",
                recording.getId(), job.getId(), job.getAttempts());

        try {
            // Schritt 1: STT fuer alle Segmente ohne Transkript. Bei einer
            // erneuten Transkription (redoTranscripts) werden auch vorhandene
            // Transkripte neu erstellt - z.B. nach einem Whisper-Konfigurationsfix.
            boolean redoTranscripts = job.isRedoTranscripts();
            // Sprechererkennung: vom Nutzer fuer diese Aufnahme gewuenscht UND vom Admin freigeschaltet
            boolean diarize = recording.isDiarize() && settings.getBool(SettingsService.WHISPER_DIARIZE);
            // Sprache der Aufnahme geht vor; ohne Wahl gilt der Admin-Standard.
            String sttLanguage = recording.getSttLanguage();
            List<RecordingSegment> segments = segmentRepo.findByRecordingIdOrderBySeq(recording.getId());
            boolean sttFailed = false;
            String sttError = null;
            for (RecordingSegment segment : segments) {
                if (segment.getStatus() != RecordingSegment.Status.READY) continue;
                if (!redoTranscripts
                        && segment.getTranscriptText() != null && !segment.getTranscriptText().isBlank()) continue;
                WhisperClient.TranscriptionResult result =
                        whisper.transcribe(Path.of(segment.getMp3Path()), diarize, sttLanguage);
                if (result.success()) {
                    segment.setTranscriptText(result.text());
                    segmentRepo.save(segment);
                } else {
                    sttFailed = true;
                    sttError = result.error();
                    segment.setError(result.error());
                    segmentRepo.save(segment);
                    log.error("STT fuer Segment {} fehlgeschlagen: {}", segment.getSeq(), result.error());
                }
            }
            if (sttFailed) {
                retryOrFail(job, recording, "STT teilweise fehlgeschlagen: " + sttError);
                return;
            }

            // Schritt 1b: KI-Glaettung des Rohtranskripts. Das Original bleibt
            // erhalten (Umschalter im Frontend), die Auswertung nutzt die
            // geglaettete Fassung. Ein Fehlschlag ist NICHT fatal - dann wird
            // weiter mit dem Original gearbeitet.
            segments = segmentRepo.findByRecordingIdOrderBySeq(recording.getId());
            correctTranscripts(recording, segments, redoTranscripts);

            // Transkript-Mindestlaenge pruefen (Teil der Lohnt-sich-Pruefung)
            segments = segmentRepo.findByRecordingIdOrderBySeq(recording.getId());
            int transcriptChars = segments.stream()
                    .map(RecordingSegment::getTranscriptText)
                    .filter(t -> t != null)
                    .mapToInt(String::length)
                    .sum();
            // Erkannte Diarisierungs-Sprecher als editierbare Teilnehmer festhalten
            // (die Glaettung laesst die Sprecherzeilen unberuehrt)
            participantService.syncFromEntries(recording.getId(), TranscriptAssembler.assemble(segments));
            writeTranscriptFiles(recording, segments);

            // Zwei-Schritt-Auswertung: nach der Transkription stoppen, die
            // Zusammenfassung stoesst der Nutzer separat an ("Jetzt auswerten").
            if (job.isTranscribeOnly()) {
                saveStatus(recording.getId(), Recording.Status.TRANSCRIBED);
                finishJob(job, true, "Nur Transkription (" + transcriptChars
                        + " Zeichen) - Zusammenfassung wird separat angestossen");
                return;
            }

            int minTranscript = settings.getInt(SettingsService.SUMMARY_MIN_TRANSCRIPT_CHARS);
            int minChat = settings.getInt(SettingsService.SUMMARY_MIN_CHAT_CHARS);
            int chatChars = recording.getChatLog() == null ? 0 : recording.getChatLog().trim().length();
            if (transcriptChars < minTranscript && chatChars < minChat) {
                saveStatus(recording.getId(), Recording.Status.DONE);
                finishJob(job, true, "Kein auswertbarer Inhalt (Transkript " + transcriptChars
                        + " Zeichen, Chat " + chatChars + " Zeichen) - keine Zusammenfassung erstellt");
                return;
            }

            // Schritt 2: Zusammenfassung
            var summary = summaryService.summarize(recording, segments);
            if (summary.getStatus() == bbbbot.domain.Summary.Status.DONE) {
                if (job.isReplaceExisting()) {
                    // Erneute Auswertung: alte Zusammenfassungen erst nach Erfolg
                    // entfernen, damit bei einem Fehlschlag nichts verloren geht.
                    summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId()).stream()
                            .filter(s -> !s.getId().equals(summary.getId()))
                            .forEach(summaryRepo::delete);
                    log.info("Alte Zusammenfassungen von Aufnahme {} durch neue Auswertung ersetzt",
                            recording.getId());
                }
                saveStatus(recording.getId(), Recording.Status.DONE);
                finishJob(job, true, null);
            } else {
                retryOrFail(job, recording, "Zusammenfassung fehlgeschlagen: " + summary.getError());
            }
        } catch (RuntimeException e) {
            log.error("Unerwarteter Fehler bei Job {}: {}", job.getId(), e.getMessage(), e);
            retryOrFail(job, recording, "Unerwarteter Fehler: " + e.getMessage());
        }
    }

    /**
     * Glaettet die Rohtranskripte der Segmente per LLM (Zwischenschritt zwischen
     * Spracherkennung und Auswertung). Das Original bleibt immer stehen; die
     * geglaettete Fassung landet zusaetzlich am Segment.
     *
     * <p>Bewusst nicht fatal: Faellt die Glaettung aus (LLM nicht erreichbar,
     * unbrauchbare Antwort), laufen Auswertung und Anzeige mit dem Original
     * weiter - ein Transkript ist mehr wert als ein abgebrochener Job.
     *
     * @param redo bei erneuter Transkription: vorhandene Glaettungen sind
     *             veraltet (sie gehoeren zu einem anderen Original) und werden verworfen
     */
    private void correctTranscripts(Recording recording, List<RecordingSegment> segments, boolean redo) {
        boolean enabled = correctionService.isEnabled();
        if (!enabled && !redo) return;

        if (redo) {
            // Veraltete Glaettung entfernen - sie passt nicht mehr zum neuen Original.
            for (RecordingSegment segment : segments) {
                if (segment.getCorrectedText() != null) {
                    segment.setCorrectedText(null);
                    segmentRepo.save(segment);
                }
            }
            saveCorrectionStatus(recording.getId(), Recording.CorrectionStatus.NONE);
            if (!enabled) return;
        }

        String glossary = correctionService.glossaryFor(recording.getOwnerId());
        int corrected = 0;
        int failed = 0;
        int skipped = 0;
        boolean llmDown = false;
        for (RecordingSegment segment : segments) {
            if (segment.getStatus() != RecordingSegment.Status.READY) continue;
            if (segment.getTranscriptText() == null || segment.getTranscriptText().isBlank()) continue;
            if (segment.getCorrectedText() != null && !segment.getCorrectedText().isBlank()) continue;
            // Hat das Modell beim vorherigen Segment nicht geantwortet, bringt es
            // nichts, die restlichen Segmente einzeln in denselben Timeout zu
            // schicken - die Auswertung soll zur Zusammenfassung weitergehen.
            if (llmDown) {
                skipped++;
                continue;
            }

            var result = correctionService.correct(segment, glossary);
            if (result.success()) {
                segment.setCorrectedText(result.text());
                segmentRepo.save(segment);
                corrected++;
                log.info("Segment {} geglaettet in {} von {} Schritt(en) in {} s "
                                + "(langsamster Schritt {} s): {} Saetze korrigiert, "
                                + "{} im Original belassen",
                        segment.getSeq(), result.steps(), result.plannedSteps(),
                        result.totalMs() / 1000, result.slowestStepMs() / 1000,
                        result.correctedSentences(), result.keptSentences());
            } else {
                failed++;
                log.warn("Glaettung von Segment {} fehlgeschlagen nach {} s ({}) - "
                                + "Original bleibt bestehen",
                        segment.getSeq(), result.totalMs() / 1000, result.error());
            }
            if (result.llmUnavailable()) llmDown = true;
        }
        if (llmDown) {
            log.warn("Glaettung fuer Aufnahme {} abgebrochen: Das LLM hat nicht geantwortet. "
                            + "{} Segment(e) uebersprungen. Pruefen: llm.baseUrl erreichbar, "
                            + "llm.timeoutSec (aktuell {} s) und correction.chunkChars "
                            + "(aktuell {} Zeichen je Aufruf) - die Auswertung laeuft mit dem "
                            + "Original-Transkript weiter.",
                    recording.getId(), skipped,
                    settings.getInt(SettingsService.LLM_TIMEOUT_SEC),
                    settings.getInt(SettingsService.CORRECTION_CHUNK_CHARS));
        }
        if (corrected == 0 && failed == 0) return;

        saveCorrectionStatus(recording.getId(), corrected > 0
                ? Recording.CorrectionStatus.READY : Recording.CorrectionStatus.FAILED);
        log.info("Glaettung fuer Aufnahme {}: {} Segment(e) geglaettet, {} fehlgeschlagen",
                recording.getId(), corrected, failed);
    }

    private void retryOrFail(ProcessingJob job, Recording recording, String error) {
        if (job.getAttempts() < MAX_JOB_ATTEMPTS) {
            job.setStatus(ProcessingJob.Status.PENDING);
            job.setLastError(error);
            jobRepo.save(job);
            saveStatus(recording.getId(), interimStatus(recording));
            log.warn("Job {} wird erneut versucht ({}). Fehler: {}", job.getId(), job.getAttempts(), error);
        } else {
            saveStatus(recording.getId(), Recording.Status.FAILED);
            finishJob(job, false, error);
        }
    }

    /**
     * Zwischenstatus bis zum naechsten Versuch: Ist die Transkription bereits
     * vollstaendig (Fehler lag in Schritt 2, der Zusammenfassung), bleibt die
     * Aufnahme TRANSCRIBED - sonst wuerde die UI sie faelschlich als "noch
     * nicht transkribiert" anbieten. Andernfalls zurueck auf RECORDED.
     */
    private Recording.Status interimStatus(Recording recording) {
        List<RecordingSegment> segments = segmentRepo.findByRecordingIdOrderBySeq(recording.getId());
        boolean hasReady = segments.stream().anyMatch(s -> s.getStatus() == RecordingSegment.Status.READY);
        boolean allTranscribed = hasReady && segments.stream()
                .filter(s -> s.getStatus() == RecordingSegment.Status.READY)
                .allMatch(s -> s.getTranscriptText() != null && !s.getTranscriptText().isBlank());
        return allTranscribed ? Recording.Status.TRANSCRIBED : Recording.Status.RECORDED;
    }

    /**
     * Setzt den Status einer Aufnahme auf einer FRISCH geladenen Entity.
     *
     * <p>Bewusst kein {@code save()} der beim Job-Start geladenen Instanz: Ein
     * Job laeuft Minuten bis Stunden (Whisper, Glaettung, Zusammenfassung),
     * waehrenddessen haengt das parallele Video-Muxen sein Ergebnis an dieselbe
     * Zeile. Da die Entity detached ist ({@code open-in-view: false}, keine
     * Transaktion), schreibt {@code save()} ALLE Spalten aus dem alten
     * Schnappschuss zurueck - das fertige Video landete so wieder auf MUXING
     * mit leerem Pfad und kam im Frontend nie an.
     */
    private void saveStatus(UUID recordingId, Recording.Status status) {
        recordingRepo.findById(recordingId).ifPresent(fresh -> {
            fresh.setStatus(status);
            recordingRepo.save(fresh);
        });
    }

    /** Wie {@link #saveStatus}, aber fuer den Glaettungs-Status. */
    private void saveCorrectionStatus(UUID recordingId, Recording.CorrectionStatus status) {
        recordingRepo.findById(recordingId).ifPresent(fresh -> {
            fresh.setCorrectionStatus(status);
            recordingRepo.save(fresh);
        });
    }

    private void finishJob(ProcessingJob job, boolean success, String message) {
        job.setStatus(success ? ProcessingJob.Status.DONE : ProcessingJob.Status.FAILED);
        job.setLastError(message);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
        log.info("Job {} abgeschlossen: {}{}", job.getId(), success ? "OK" : "FEHLER",
                message == null ? "" : " (" + message + ")");
    }

    /** Schreibt die Transkript-Dateien neu, z.B. nachdem ein Teilnehmer umbenannt wurde. */
    public void rewriteTranscriptFile(Recording recording) {
        writeTranscriptFiles(recording, segmentRepo.findByRecordingIdOrderBySeq(recording.getId()));
    }

    /**
     * Schreibt {@code transcript.md} (geglaettete Fassung, sofern vorhanden) und -
     * sobald eine Glaettung existiert - zusaetzlich {@code transcript_original.md}
     * mit dem unveraenderten Whisper-Ergebnis. So bleibt die Rohfassung auch
     * ausserhalb der Anwendung nachvollziehbar.
     */
    private void writeTranscriptFiles(Recording recording, List<RecordingSegment> segments) {
        var names = participantService.nameMap(recording.getId());
        boolean hasCorrection = segments.stream()
                .anyMatch(s -> s.getCorrectedText() != null && !s.getCorrectedText().isBlank());

        writeTranscript(recording, "transcript.md",
                TranscriptAssembler.assemble(segments, true), names);
        if (hasCorrection) {
            writeTranscript(recording, "transcript_original.md",
                    TranscriptAssembler.assemble(segments, false), names);
        }
    }

    private void writeTranscript(Recording recording, String filename,
                                 List<TranscriptAssembler.Entry> entries,
                                 java.util.Map<String, String> names) {
        try {
            // Zusammengefuehrtes Gesamt-Transkript mit fortlaufenden Zeitstempeln;
            // Diarisierungs-Labels werden durch die gepflegten Teilnehmernamen ersetzt
            String text = TranscriptAssembler.toText(TranscriptAssembler.applyNames(entries, names));
            if (!text.isBlank()) {
                Files.writeString(Path.of(recording.getDirectory()).resolve(filename),
                        text + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("{} konnte nicht geschrieben werden: {}", filename, e.getMessage());
        }
    }
}
