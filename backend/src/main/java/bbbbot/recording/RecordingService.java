package bbbbot.recording;

import bbbbot.config.AppProperties;
import bbbbot.domain.BotSession;
import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.media.FfmpegService;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Verwaltet den Lebenszyklus von Aufnahmen: Verzeichnisse, Segment-Registrierung,
 * asynchrone Transkodierung und Finalisierung (inkl. Lohnt-sich-Pruefung und
 * Anlage des Verarbeitungs-Jobs). Laeuft bewusst NICHT auf dem Playwright-Thread
 * der Bot-Instanz.
 */
@Service
public class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    private final AppProperties props;
    private final FfmpegService ffmpeg;
    private final SettingsService settings;
    private final RecordingRepo recordingRepo;
    private final RecordingSegmentRepo segmentRepo;
    private final ProcessingJobRepo jobRepo;
    private final BotSessionRepo sessionRepo;

    private final ExecutorService transcodePool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "transcode");
        t.setDaemon(true);
        return t;
    });

    /** Laufende Transkodierungen pro Aufnahme, damit die Finalisierung darauf warten kann. */
    private final Map<UUID, List<CompletableFuture<Void>>> pendingTranscodes = new ConcurrentHashMap<>();

    /** Laufende Finalisierungen pro Aufnahme, damit das Video-Muxen danach laufen kann. */
    private final Map<UUID, CompletableFuture<Void>> finalizeFutures = new ConcurrentHashMap<>();

    /** Eigener Thread fuers CPU-intensive Video-Muxen (blockiert die Transkodierung nicht). */
    private final ExecutorService videoMuxPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-mux");
        t.setDaemon(true);
        return t;
    });

    /** Aufnahmen, deren Video gerade gemuxt wird oder in der Warteschlange steht. */
    private final Set<UUID> activeMuxes = ConcurrentHashMap.newKeySet();

    /**
     * Schonfrist, bevor {@link #sweepStuckVideos()} ein Video als haengend
     * ansieht. Deckt die laengstmoegliche regulaere Mux-Dauer ab (Warten auf die
     * Finalisierung plus ffmpeg-Laufzeit).
     */
    private static final long STUCK_VIDEO_GRACE_MINUTES = 90;

    public RecordingService(AppProperties props, FfmpegService ffmpeg, SettingsService settings,
                            RecordingRepo recordingRepo, RecordingSegmentRepo segmentRepo,
                            ProcessingJobRepo jobRepo, BotSessionRepo sessionRepo) {
        this.props = props;
        this.ffmpeg = ffmpeg;
        this.settings = settings;
        this.recordingRepo = recordingRepo;
        this.segmentRepo = segmentRepo;
        this.jobRepo = jobRepo;
        this.sessionRepo = sessionRepo;
    }

    /**
     * @param sttLanguage Sprache der Spracherkennung dieser Aufnahme; null =
     *                    Admin-Standard, "auto" = Whisper erkennt sie selbst
     */
    public Recording createRecording(UUID botSessionId, UUID ownerId, String meetingUrl,
                                     boolean recordVideo, boolean aiAnalysis, boolean diarize,
                                     String sttLanguage, String title) {
        Recording recording = Recording.start(botSessionId, ownerId, meetingUrl, "", recordVideo, aiAnalysis, diarize);
        recording.setSttLanguage(sttLanguage);
        if (title != null && !title.isBlank()) {
            recording.setTitle(title.trim());
        }
        Path dir = Path.of(props.getStorage().getRootDir()).resolve(recording.getId().toString());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Aufnahme-Verzeichnis kann nicht angelegt werden: " + dir, e);
        }
        recording.setDirectory(dir.toAbsolutePath().toString());
        recordingRepo.save(recording);
        log.info("Aufnahme {} angelegt (Verzeichnis {}, Video={}, Analyse={})",
                recording.getId(), dir, recordVideo, aiAnalysis);
        return recording;
    }

    /**
     * Optionen einer hochgeladenen Aufnahme. Als Satz statt als lange
     * Parameterliste, weil die Wege inzwischen verschieden viel wollen: die
     * Weboberflaeche eine vollstaendige Aufnahme, die API-Transkription nur den
     * Text.
     *
     * @param title          Anzeigetitel; leer bedeutet Dateiname
     * @param aiAnalysis     Transkription/Auswertung ueberhaupt gewuenscht
     * @param processNow     sofort auswerten statt im Nachtfenster
     * @param diarize        Sprechererkennung (muss vom Admin freigeschaltet sein)
     * @param transcribeOnly nur transkribieren, keine Zusammenfassung
     * @param keepVideo      Video zusaetzlich abspielbar bereitstellen; fuer eine
     *                       reine Transkription unnoetige Rechenzeit
     * @param summaryPrompt  Auswertungs-Prompt dieser Aufnahme; null = Admin-Standard.
     *                       Er wird an der Aufnahme gespeichert und wirkt damit
     *                       schon bei der ersten (auch sofortigen) Auswertung.
     * @param summaryTemplateName Name der gewaehlten Vorlage; null = keine benannte.
     *                       Er benennt spaeter die erzeugte Fassung.
     * @param summaryModel   Modell fuer die Auswertung; null = Admin-Vorgabe
     * @param summaryTemperature Temperatur fuer die Auswertung; null = Admin-Vorgabe
     * @param sttLanguage    Sprache der Spracherkennung; null = Admin-Standard,
     *                       "auto" = Whisper erkennt sie selbst. Sie muss - wie der
     *                       Prompt - vor dem Verarbeitungs-Job feststehen.
     */
    public record UploadOptions(String title, boolean aiAnalysis, boolean processNow,
                                boolean diarize, boolean transcribeOnly, boolean keepVideo,
                                String summaryPrompt, String summaryTemplateName,
                                String summaryModel, Double summaryTemperature,
                                String sttLanguage) {

        /** Upload aus der Weboberflaeche: vollstaendige Aufnahme inkl. Video. */
        public static UploadOptions forUpload(String title, boolean aiAnalysis, boolean processNow,
                                              boolean diarize, String summaryPrompt,
                                              String summaryTemplateName, String summaryModel,
                                              Double summaryTemperature, String sttLanguage) {
            return new UploadOptions(title, aiAnalysis, aiAnalysis && processNow, diarize, false, true,
                    summaryPrompt, summaryTemplateName, summaryModel, summaryTemperature, sttLanguage);
        }

        /** API-Transkription: sofort transkribieren, keine Zusammenfassung, kein Video. */
        public static UploadOptions forTranscription(String title, boolean diarize, String sttLanguage) {
            return new UploadOptions(title, true, true, diarize, true, false, null, null, null, null,
                    sttLanguage);
        }
    }

    /**
     * Legt eine Aufnahme aus einer hochgeladenen Datei an: Die Datei wird im
     * Aufnahme-Verzeichnis abgelegt, asynchron in MP3-Segmente fester Laenge
     * transkodiert (ffmpeg erkennt das Eingabeformat selbst, mehrere Tonspuren
     * werden gemischt, Video-Container werden zu Audio extrahiert) und danach
     * finalisiert - bei gewuenschter KI-Analyse inkl. Verarbeitungs-Job.
     * Bewusst KEINE Lohnt-sich-Verwerfung: Was der Nutzer explizit hochlaedt,
     * wird behalten.
     */
    public Recording createUploadedRecording(UUID ownerId, UploadOptions options,
                                             String originalFilename,
                                             java.io.InputStream data) throws IOException {
        String title = options.title();
        boolean aiAnalysis = options.aiAnalysis();
        Recording recording = Recording.start(null, ownerId, null, "", false, aiAnalysis, options.diarize());
        recording.setSource(Recording.Source.UPLOAD);
        recording.setStatus(Recording.Status.FINALIZING);
        recording.setTitle(title == null || title.isBlank() ? originalFilename : title.trim());
        // Beim Hochladen gewaehlte Auswertungs-Vorlage; sie muss VOR dem
        // Verarbeitungs-Job an der Aufnahme stehen, damit auch eine sofortige
        // Auswertung schon mit ihr laeuft.
        recording.setSummaryPrompt(options.summaryPrompt());
        recording.setSummaryTemplateName(options.summaryTemplateName());
        recording.setSummaryModel(options.summaryModel());
        recording.setSummaryTemperature(options.summaryTemperature());
        // Dasselbe gilt fuer die Sprache der Spracherkennung: Sie wird beim
        // Transkribieren gebraucht, das direkt im Anschluss laufen kann.
        recording.setSttLanguage(options.sttLanguage());
        Path dir = Path.of(props.getStorage().getRootDir()).resolve(recording.getId().toString());
        Files.createDirectories(dir);
        recording.setDirectory(dir.toAbsolutePath().toString());

        Path source = dir.resolve("upload_" + sanitizeFilename(originalFilename));
        Files.copy(data, source);

        // Enthaelt die Datei einen echten Video-Stream (Cover-Art zaehlt nicht),
        // wird sie zusaetzlich als abspielbares Video bereitgestellt.
        boolean hasVideo = options.keepVideo() && hasVideoStream(source);
        if (hasVideo) {
            recording.setRecordVideo(true);
            recording.setVideoStatus(Recording.VideoStatus.MUXING);
        }
        recordingRepo.save(recording);

        processSourceFile(recording.getId(), source, hasVideo, options.processNow(),
                options.transcribeOnly());
        log.info("Upload-Aufnahme {} angelegt ({}, {} Bytes, Video={}, Analyse={}, Diarisierung={}, nurTranskript={})",
                recording.getId(), source.getFileName(), Files.size(source), hasVideo, aiAnalysis,
                options.diarize(), options.transcribeOnly());
        return recording;
    }

    /**
     * Uebergibt eine fertige Quelldatei (hochgeladen oder im Browser aufgezeichnet)
     * an die Verarbeitung: Transkodierung in MP3-Segmente, optional Bereitstellung
     * als abspielbares Video, danach Finalisierung inkl. Verarbeitungs-Job.
     * Der Aufrufer hat {@code recordVideo}/{@code videoStatus} passend zu
     * {@code hasVideo} bereits gesetzt und die Aufnahme gespeichert.
     */
    public void processSourceFile(UUID recordingId, Path source, boolean hasVideo, boolean processNow) {
        processSourceFile(recordingId, source, hasVideo, processNow, false);
    }

    /**
     * Wie {@link #processSourceFile(UUID, Path, boolean, boolean)}, aber mit der
     * Wahl, den Verarbeitungs-Job auf die reine Transkription zu beschraenken
     * (API-Transkription: der Aufrufer will nur den Text, keine Zusammenfassung).
     */
    public void processSourceFile(UUID recordingId, Path source, boolean hasVideo, boolean processNow,
                                  boolean transcribeOnly) {
        transcodePool.execute(() -> {
            transcodeSourceFile(recordingId, source);
            finalizeSourceFile(recordingId, processNow, transcribeOnly);
        });
        if (hasVideo) {
            activeMuxes.add(recordingId);
            videoMuxPool.execute(() -> {
                try {
                    convertSourceVideo(recordingId, source);
                } catch (RuntimeException e) {
                    // Ohne dieses Netz bliebe die Aufnahme fuer immer auf MUXING stehen.
                    log.error("Video-Bereitstellung fuer Aufnahme {} unerwartet abgebrochen: {}",
                            recordingId, e.getMessage(), e);
                    markVideoFailed(recordingId);
                } finally {
                    activeMuxes.remove(recordingId);
                }
            });
        }
    }

    /**
     * Prueft, ob die Datei einen echten Video-Stream enthaelt (angehaengte
     * Cover-Art zaehlt nicht). Reine Audio-Endungen werden gar nicht erst geprobt.
     */
    public boolean hasVideoStream(Path source) {
        return isVideoContainer(source.getFileName().toString()) && ffmpeg.videoStreamCodec(source) != null;
    }

    /**
     * Transkodiert die Quelldatei in MP3-Segmente (Laenge wie bei Bot-Aufnahmen,
     * Setting recording.segmentMinutes) und registriert sie.
     * Laeuft auf dem Transcode-Pool.
     */
    private void transcodeSourceFile(UUID recordingId, Path source) {
        Recording recording = recordingRepo.findById(recordingId).orElse(null);
        if (recording == null) return;
        Path dir = Path.of(recording.getDirectory());
        int segmentSeconds = (int) (settings.getLong(SettingsService.RECORDING_SEGMENT_MINUTES) * 60);
        String bitrate = settings.get(SettingsService.RECORDING_MP3_BITRATE);

        FfmpegService.SplitResult result = ffmpeg.transcodeToMp3Segments(source, dir, segmentSeconds, bitrate);
        if (!result.success()) {
            // Fehler an einem Platzhalter-Segment festhalten, damit finalizeSourceFile
            // ihn dem Nutzer anzeigen kann.
            RecordingSegment segment = registerSegment(recordingId, 0, source);
            segment.setStatus(RecordingSegment.Status.FAILED);
            segment.setError(result.error());
            segmentRepo.save(segment);
            log.error("Transkodierung der Quelldatei fuer {} fehlgeschlagen: {}", recordingId, result.error());
            return;
        }
        int seq = 0;
        for (Path mp3 : result.parts()) {
            RecordingSegment segment = RecordingSegment.create(recordingId, seq++, source.toAbsolutePath().toString());
            segment.setMp3Path(mp3.toAbsolutePath().toString());
            segment.setDurationMs(ffmpeg.probeDurationMs(mp3));
            try {
                segment.setSizeBytes(Files.size(mp3));
            } catch (IOException ignored) {}
            segment.setStatus(RecordingSegment.Status.READY);
            segmentRepo.save(segment);
        }
        log.info("Quelldatei von Aufnahme {} in {} MP3-Segment(e) transkodiert",
                recordingId, result.parts().size());
    }

    /** Container-Endungen, hinter denen ein Video stecken kann (reine Audio-Formate nicht proben). */
    private static final java.util.Set<String> VIDEO_CONTAINER_EXTENSIONS =
            java.util.Set.of("mp4", "mkv", "mov", "avi", "webm", "3gp", "ts");

    private static boolean isVideoContainer(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_CONTAINER_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    /** Stellt die Quelldatei zusaetzlich als abspielbares meeting.mp4 bereit (Video-Mux-Pool). */
    private void convertSourceVideo(UUID recordingId, Path source) {
        Recording recording = recordingRepo.findById(recordingId).orElse(null);
        if (recording == null) return;
        Path outMp4 = Path.of(recording.getDirectory()).resolve("meeting.mp4");
        FfmpegService.MuxResult result = ffmpeg.convertToMp4(source, outMp4);

        // Gezieltes Update statt save(): Die Transkodierung/Finalisierung derselben
        // Aufnahme laeuft parallel und wuerde sonst mit ihrem alten Schnappschuss
        // den fertigen Video-Status wieder ueberschreiben.
        if (result.success()) {
            recordingRepo.updateVideoState(recordingId, Recording.VideoStatus.READY,
                    outMp4.toAbsolutePath().toString());
            log.info("Video fuer Aufnahme {} bereitgestellt: {}", recordingId, outMp4.getFileName());
        } else {
            markVideoFailed(recordingId);
            log.error("Video fuer Aufnahme {} fehlgeschlagen: {}", recordingId, result.error());
        }
    }

    /**
     * Schliesst die Aufnahme auf einer FRISCH geladenen Entity ab (Ende, Dauer
     * und was {@code change} sonst setzt).
     *
     * <p>Bewusst neu geladen: Die Bereitstellung des Videos laeuft parallel auf
     * dem Mux-Pool und schreibt in dieselbe Zeile. Da die Entity detached ist,
     * wuerde ein save() aus einem aelteren Schnappschuss alle Spalten
     * zurueckschreiben - ein zwischenzeitlich fertiges Video landete wieder auf
     * MUXING und kaeme im Frontend nie an.
     */
    private void updateFinalizedRecording(UUID recordingId, long totalDurationMs,
                                          Consumer<Recording> change) {
        recordingRepo.findById(recordingId).ifPresent(fresh -> {
            fresh.setEndedAt(Instant.now());
            fresh.setDurationMs(totalDurationMs);
            change.accept(fresh);
            recordingRepo.save(fresh);
        });
    }

    /**
     * Finalisierung einer Aufnahme aus einer Quelldatei (Upload oder
     * Bildschirmaufnahme) nach der Transkodierung (Transcode-Pool).
     */
    private void finalizeSourceFile(UUID recordingId, boolean processNow, boolean transcribeOnly) {
        Recording recording = recordingRepo.findById(recordingId).orElse(null);
        if (recording == null) return;

        List<RecordingSegment> segments = segmentRepo.findByRecordingIdOrderBySeq(recordingId);
        long totalDurationMs = segments.stream()
                .filter(s -> s.getDurationMs() != null)
                .mapToLong(RecordingSegment::getDurationMs)
                .sum();

        boolean anyReady = segments.stream().anyMatch(s -> s.getStatus() == RecordingSegment.Status.READY);
        if (!anyReady) {
            String error = segments.stream()
                    .map(RecordingSegment::getError)
                    .filter(e -> e != null && !e.isBlank())
                    .findFirst()
                    .orElse("Datei enthaelt kein verwertbares Audio");
            // Auf frischem Stand speichern: Die Video-Bereitstellung derselben
            // Aufnahme laeuft parallel und hat den Video-Status womoeglich schon
            // fortgeschrieben - ein alter Schnappschuss wuerde ihn zurueckdrehen.
            updateFinalizedRecording(recordingId, totalDurationMs, r -> {
                r.setStatus(Recording.Status.FAILED);
                r.setDiscardReason("Aufnahmedatei konnte nicht verarbeitet werden: " + error);
            });
            log.warn("Aufnahme {} fehlgeschlagen: {}", recordingId, error);
            return;
        }

        if (!recording.isAiAnalysis()) {
            updateFinalizedRecording(recordingId, totalDurationMs, r -> r.setStatus(Recording.Status.DONE));
            log.info("Aufnahme {} fertig ohne KI-Analyse ({} ms Audio)", recordingId, totalDurationMs);
            return;
        }
        updateFinalizedRecording(recordingId, totalDurationMs, r -> r.setStatus(Recording.Status.RECORDED));
        ProcessingJob job = ProcessingJob.create(recordingId, processNow);
        job.setTranscribeOnly(transcribeOnly);
        jobRepo.save(job);
        log.info("Aufnahme {} fertig ({} ms Audio) - Verarbeitungs-Job angelegt (sofort={}, nurTranskript={})",
                recordingId, totalDurationMs, processNow, transcribeOnly);
    }

    /** Dateinamen fuer die Ablage im Aufnahme-Verzeichnis absichern. */
    private static String sanitizeFilename(String name) {
        String cleaned = (name == null ? "" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.startsWith(".")) cleaned = "aufnahme_" + cleaned;
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    /** Temporaeres Verzeichnis fuer die Playwright-Video-Aufzeichnung einer Bot-Session. */
    public Path videoTmpDir(UUID sessionId) {
        Path dir = Path.of(props.getStorage().getRootDir()).resolve("video-tmp").resolve(sessionId.toString());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Video-Temp-Verzeichnis kann nicht angelegt werden: " + dir, e);
        }
        return dir;
    }

    public java.util.Optional<String> findDirectory(UUID recordingId) {
        return recordingRepo.findById(recordingId).map(Recording::getDirectory);
    }

    public RecordingSegment registerSegment(UUID recordingId, int seq, Path webmPath) {
        RecordingSegment segment = RecordingSegment.create(recordingId, seq, webmPath.toAbsolutePath().toString());
        return segmentRepo.save(segment);
    }

    /** Wird aufgerufen, sobald ein webm-Segment fertig geschrieben ist; transkodiert asynchron. */
    public void segmentFinished(RecordingSegment segment) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> transcodeSegment(segment.getId()), transcodePool);
        pendingTranscodes.computeIfAbsent(segment.getRecordingId(), k -> new ArrayList<>()).add(future);
    }

    private void transcodeSegment(UUID segmentId) {
        RecordingSegment segment = segmentRepo.findById(segmentId).orElse(null);
        if (segment == null) return;
        try {
            Path webm = Path.of(segment.getWebmPath());
            long size = Files.exists(webm) ? Files.size(webm) : 0;
            segment.setSizeBytes(size);
            long minBytes = settings.getLong(SettingsService.RECORDING_MIN_AUDIO_BYTES);
            if (size < minBytes) {
                segment.setStatus(RecordingSegment.Status.EMPTY);
                segmentRepo.save(segment);
                log.info("Segment {} ist leer ({} bytes < {}), keine Transkodierung", segment.getSeq(), size, minBytes);
                return;
            }
            segment.setStatus(RecordingSegment.Status.TRANSCODING);
            segmentRepo.save(segment);

            Path mp3 = webm.resolveSibling(webm.getFileName().toString().replaceAll("\\.webm$", "") + ".mp3");
            String bitrate = settings.get(SettingsService.RECORDING_MP3_BITRATE);
            FfmpegService.TranscodeResult result = ffmpeg.transcodeWebmToMp3(webm, mp3, bitrate);
            if (result.success()) {
                segment.setMp3Path(result.mp3Path().toAbsolutePath().toString());
                segment.setDurationMs(result.durationMs());
                segment.setStatus(RecordingSegment.Status.READY);
                log.info("Segment {} transkodiert: {} ({} ms)", segment.getSeq(), mp3.getFileName(), result.durationMs());
            } else {
                segment.setStatus(RecordingSegment.Status.FAILED);
                segment.setError(result.error());
                log.error("Segment {} Transkodierung endgueltig fehlgeschlagen: {}", segment.getSeq(), result.error());
            }
            segmentRepo.save(segment);
        } catch (IOException e) {
            segment.setStatus(RecordingSegment.Status.FAILED);
            segment.setError(e.getMessage());
            segmentRepo.save(segment);
        }
    }

    /**
     * Finalisiert eine Aufnahme asynchron: wartet auf alle Transkodierungen,
     * schreibt Protokolle, prueft ob sich eine Auswertung lohnt und legt den
     * Verarbeitungs-Job an.
     */
    public CompletableFuture<Void> finalizeRecording(UUID recordingId, String participantsLog, String chatLog,
                                                     boolean discard, String reason) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            Recording recording = recordingRepo.findById(recordingId).orElse(null);
            if (recording == null) return;
            recording.setStatus(Recording.Status.FINALIZING);
            recording.setEndedAt(Instant.now());
            recording.setParticipantsLog(participantsLog);
            recording.setChatLog(chatLog);
            recordingRepo.save(recording);

            if (discard) {
                discardRecording(recording, reason);
                return;
            }

            waitForTranscodes(recordingId);

            List<RecordingSegment> segments = segmentRepo.findByRecordingIdOrderBySeq(recordingId);
            long totalDurationMs = segments.stream()
                    .filter(s -> s.getDurationMs() != null)
                    .mapToLong(RecordingSegment::getDurationMs)
                    .sum();
            recording.setDurationMs(totalDurationMs);

            writeSidecarFiles(recording);

            // Ohne gewuenschte KI-Analyse wird die Aufnahme unveraendert behalten
            // (kein Whisper/LLM). Die Lohnt-sich-/Verwerf-Pruefung ist eine reine
            // Analyse-Kostenoptimierung und entfaellt daher hier.
            if (!recording.isAiAnalysis()) {
                recording.setStatus(Recording.Status.DONE);
                recordingRepo.save(recording);
                log.info("Aufnahme {} finalisiert ohne KI-Analyse ({} ms Audio, {} Segmente, Video={})",
                        recordingId, totalDurationMs, segments.size(), recording.isRecordVideo());
                return;
            }

            // Lohnt-sich-Pruefung (Portierung des Upload-Deciders): ODER-Logik
            long minAudioMs = settings.getLong(SettingsService.SUMMARY_MIN_AUDIO_MS);
            int minChatChars = settings.getInt(SettingsService.SUMMARY_MIN_CHAT_CHARS);
            boolean hasAudio = totalDurationMs >= minAudioMs;
            boolean hasChat = chatLog != null && chatLog.trim().length() >= minChatChars;
            boolean anyReadySegment = segments.stream().anyMatch(s -> s.getStatus() == RecordingSegment.Status.READY);

            // Bei Video-Aufnahme nicht wegen zu wenig Audio verwerfen - das Video ist der Zweck.
            if (!recording.isRecordVideo() && (!anyReadySegment || (!hasAudio && !hasChat))) {
                recording.setStatus(Recording.Status.DISCARDED);
                recording.setDiscardReason("Zu wenig Inhalt: Audio %d ms (min %d), Chat %d Zeichen (min %d)".formatted(
                        totalDurationMs, minAudioMs, chatLog == null ? 0 : chatLog.trim().length(), minChatChars));
                recordingRepo.save(recording);
                log.info("Aufnahme {} verworfen: {}", recordingId, recording.getDiscardReason());
                return;
            }

            recording.setStatus(Recording.Status.RECORDED);
            recordingRepo.save(recording);
            jobRepo.save(ProcessingJob.create(recordingId, false));
            log.info("Aufnahme {} finalisiert ({} ms Audio, {} Segmente) - Verarbeitungs-Job angelegt",
                    recordingId, totalDurationMs, segments.size());
        }, transcodePool);
        finalizeFutures.put(recordingId, future);
        future.whenComplete((v, t) -> finalizeFutures.remove(recordingId));
        return future;
    }

    /**
     * Muxt die aufgezeichnete Browser-Ansicht (Video-Teile) mit dem Meeting-Audio
     * zu je einer MP4 pro Aufnahme und haengt sie an. Wartet zuvor auf deren
     * Finalisierung, damit die MP3-Segmente vorliegen. Laeuft asynchron.
     *
     * <p>Playwright zeichnet den gesamten Browser-Kontext auf. Stoppt und startet
     * eine Bot-Sitzung die Aufnahme zwischendurch (Chat-Befehl, Raum
     * zwischenzeitlich leer), gehoeren also MEHRERE Aufnahmen zu derselben
     * Videodatei. Jede bekommt ihren eigenen Ausschnitt: vorne per Offset ab
     * ihrem Aufnahmestart, hinten durch ihre eigene Audiolaenge ({@code -shortest}).
     * Die Temp-Datei wird erst nach der letzten Aufnahme geloescht.
     *
     * @param videoStartEpochMs Wall-Clock-Zeitpunkt (epoch ms), zu dem die Playwright-
     *                          Video-/Kontextaufnahme begann - fuer die A/V-Synchronisierung.
     */
    public void attachVideo(List<UUID> recordingIds, List<Path> videoParts, long videoStartEpochMs) {
        List<UUID> ids = recordingIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            deleteTempVideos(videoParts);
            return;
        }
        // Vormerken, bevor die Aufgabe in der Warteschlange steht: Der
        // Reparatur-Sweep darf wartende Aufnahmen nicht auf FAILED setzen.
        activeMuxes.addAll(ids);
        videoMuxPool.execute(() -> {
            try {
                for (UUID id : ids) {
                    try {
                        muxVideo(id, videoParts, videoStartEpochMs);
                    } catch (RuntimeException e) {
                        // Ohne dieses Netz bliebe die Aufnahme fuer immer auf MUXING
                        // stehen und das Frontend zeigte ewig "wird verarbeitet".
                        log.error("Video-Muxen fuer Aufnahme {} unerwartet abgebrochen: {}", id, e.getMessage(), e);
                        markVideoFailed(id);
                    } finally {
                        activeMuxes.remove(id);
                    }
                }
            } finally {
                activeMuxes.removeAll(ids);
                deleteTempVideos(videoParts);
            }
        });
    }

    /**
     * Kennzeichnet Aufnahmen, zu denen nie ein Video kommt (der Browser-Kontext
     * hat keine Videodatei hinterlassen). Ohne das blieben sie auf RECORDING
     * stehen und das Frontend zeigte endlos "wird verarbeitet".
     */
    public void markVideoUnavailable(List<UUID> recordingIds) {
        for (UUID id : recordingIds) {
            if (id == null) continue;
            Recording.VideoStatus current = recordingRepo.findById(id)
                    .map(Recording::getVideoStatus).orElse(null);
            if (current == Recording.VideoStatus.RECORDING || current == Recording.VideoStatus.MUXING) {
                markVideoFailed(id);
            }
        }
    }

    /** Einzelne Aufnahme aus dem Kontext-Video schneiden. Loescht die Quelldateien NICHT. */
    private void muxVideo(UUID recordingId, List<Path> videoParts, long videoStartEpochMs) {
        List<Path> parts = videoParts.stream().filter(p -> p != null && Files.exists(p)).toList();
        awaitFinalize(recordingId);
        Recording recording = recordingRepo.findById(recordingId).orElse(null);
        if (recording == null) return;
        if (recording.getStatus() == Recording.Status.DISCARDED || parts.isEmpty()) {
            markVideoFailed(recordingId);
            return;
        }
        recordingRepo.updateVideoState(recordingId, Recording.VideoStatus.MUXING, recording.getVideoPath());

        List<Path> audio = segmentRepo.findByRecordingIdOrderBySeq(recordingId).stream()
                .filter(s -> s.getStatus() == RecordingSegment.Status.READY && s.getMp3Path() != null)
                .map(s -> Path.of(s.getMp3Path()))
                .toList();
        // Vorlauf des Videos vor dem Aufnahmestart (Playwright nimmt den ganzen
        // Kontext ab Join auf) -> Video vorne abschneiden, damit A/V synchron sind.
        long offsetMs = videoStartEpochMs <= 0
                ? 0
                : Math.max(0, recording.getStartedAt().toEpochMilli() - videoStartEpochMs);
        Path outMp4 = Path.of(recording.getDirectory()).resolve("meeting.mp4");
        FfmpegService.MuxResult result = ffmpeg.muxToMp4(parts, audio, outMp4, offsetMs);

        if (result.success()) {
            recordingRepo.updateVideoState(recordingId, Recording.VideoStatus.READY,
                    outMp4.toAbsolutePath().toString());
            log.info("Video fuer Aufnahme {} erstellt: {}", recordingId, outMp4.getFileName());
        } else {
            markVideoFailed(recordingId);
            log.error("Video-Muxen fuer Aufnahme {} fehlgeschlagen: {}", recordingId, result.error());
        }
    }

    /** Video als fehlgeschlagen kennzeichnen, ohne andere Felder der Aufnahme anzufassen. */
    private void markVideoFailed(UUID recordingId) {
        try {
            recordingRepo.updateVideoState(recordingId, Recording.VideoStatus.FAILED, null);
        } catch (RuntimeException e) {
            log.warn("Video-Status von {} konnte nicht auf FAILED gesetzt werden: {}", recordingId, e.getMessage());
        }
    }

    /**
     * Sicherheitsnetz gegen dauerhaft haengende Video-Anzeigen: Aufnahmen, die
     * lange nach ihrem Ende immer noch auf RECORDING/MUXING stehen, ohne dass
     * eine Mux-Aufgabe zu ihnen laeuft. Das passiert, wenn der Prozess mit
     * gefuellter Mux-Warteschlange endet oder eine Bot-Sitzung ohne Video-Handle
     * schliesst. Liegt die MP4 bereits auf der Platte, wird sie nachtraeglich
     * eingehaengt - sonst wird ehrlich FAILED angezeigt statt endlos
     * "wird verarbeitet".
     */
    @Scheduled(fixedDelay = 15 * 60_000L, initialDelay = 120_000L)
    public void sweepStuckVideos() {
        Instant cutoff = Instant.now().minus(STUCK_VIDEO_GRACE_MINUTES, ChronoUnit.MINUTES);
        List<Recording> candidates = recordingRepo.findByVideoStatusIn(
                List.of(Recording.VideoStatus.RECORDING, Recording.VideoStatus.MUXING));
        for (Recording r : candidates) {
            if (activeMuxes.contains(r.getId())) continue;
            if (r.getEndedAt() == null || r.getEndedAt().isAfter(cutoff)) continue;
            // Laeuft die Bot-Sitzung noch, kommt das Video erst beim Schliessen
            // des Browser-Kontextes - auch fuer laengst gestoppte Aufnahmen.
            if (isSessionRunning(r.getBotSessionId())) continue;

            Path mp4 = Path.of(r.getDirectory()).resolve("meeting.mp4");
            boolean usable = Files.isRegularFile(mp4) && fileSizeQuietly(mp4) > 0;
            if (usable) {
                recordingRepo.updateVideoState(r.getId(), Recording.VideoStatus.READY,
                        mp4.toAbsolutePath().toString());
                log.info("Haengendes Video von Aufnahme {} repariert: MP4 lag bereits vor", r.getId());
            } else {
                recordingRepo.updateVideoState(r.getId(), Recording.VideoStatus.FAILED, null);
                log.warn("Video von Aufnahme {} steht seit {} auf {} ohne laufendes Muxen - auf FAILED gesetzt",
                        r.getId(), r.getEndedAt(), r.getVideoStatus());
            }
        }
    }

    private boolean isSessionRunning(UUID botSessionId) {
        if (botSessionId == null) return false;
        return sessionRepo.findById(botSessionId)
                .map(s -> s.getStatus() == BotSession.Status.STARTING
                        || s.getStatus() == BotSession.Status.JOINED
                        || s.getStatus() == BotSession.Status.RECORDING
                        || s.getStatus() == BotSession.Status.RECONNECTING)
                .orElse(false);
    }

    private static long fileSizeQuietly(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private void awaitFinalize(UUID recordingId) {
        CompletableFuture<Void> future = finalizeFutures.get(recordingId);
        if (future == null) return;
        try {
            future.get(15, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Warten auf Finalisierung von {} vor Video-Muxen fehlgeschlagen: {}", recordingId, e.getMessage());
        }
    }

    private void deleteTempVideos(List<Path> parts) {
        for (Path p : parts) {
            if (p == null) continue;
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            // leeres Session-Temp-Verzeichnis mit aufraeumen
            Path parent = p.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getParent() != null
                    && "video-tmp".equals(String.valueOf(parent.getParent().getFileName()))) {
                try (var s = Files.list(parent)) {
                    if (s.findAny().isEmpty()) Files.deleteIfExists(parent);
                } catch (IOException ignored) {}
            }
        }
    }

    private void discardRecording(Recording recording, String reason) {
        waitForTranscodes(recording.getId());
        recording.setStatus(Recording.Status.DISCARDED);
        recording.setDiscardReason(reason);
        recordingRepo.save(recording);
        // Audiodaten verworfen, Verzeichnis aufraeumen (Datenschutz: Teilnehmer hat STOP gesendet)
        try {
            Path dir = Path.of(recording.getDirectory());
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException e) {
            log.warn("Verzeichnis der verworfenen Aufnahme konnte nicht geloescht werden: {}", e.getMessage());
        }
        log.info("Aufnahme {} verworfen: {}", recording.getId(), reason);
    }

    private void waitForTranscodes(UUID recordingId) {
        List<CompletableFuture<Void>> futures = pendingTranscodes.remove(recordingId);
        if (futures == null) return;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Warten auf Transkodierungen fuer {} fehlgeschlagen: {}", recordingId, e.getMessage());
        }
    }

    private void writeSidecarFiles(Recording recording) {
        try {
            Path dir = Path.of(recording.getDirectory());
            if (recording.getParticipantsLog() != null) {
                Files.writeString(dir.resolve("participants.txt"), recording.getParticipantsLog(), StandardCharsets.UTF_8);
            }
            if (recording.getChatLog() != null) {
                Files.writeString(dir.resolve("chat.txt"), recording.getChatLog(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Protokoll-Dateien konnten nicht geschrieben werden: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        transcodePool.shutdown();
        videoMuxPool.shutdown();
    }
}
