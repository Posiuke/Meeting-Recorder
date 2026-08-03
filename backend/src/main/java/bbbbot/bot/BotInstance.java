package bbbbot.bot;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import bbbbot.config.AppProperties;
import bbbbot.domain.BotSession;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.recording.RecordingService;
import bbbbot.repository.Repositories.BotSessionRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Eine Bot-Instanz = ein Meeting-Raum = eine Headless-Chromium-Instanz.
 *
 * Alle Playwright-Aufrufe laufen auf einem einzigen, dedizierten Thread
 * (Playwright-Objekte sind nicht threadsicher). Oeffentliche Methoden aus dem
 * REST-Layer reichen ihre Arbeit als Task auf diesen Thread weiter.
 *
 * Die Ablauflogik ist die bewaehrte des alten Node-Bots: Auto-Start nach
 * Teilnehmerzahl + Audiotracks (mit Doppel-Bestaetigung ueber zwei Ticks),
 * Chat-Befehle mit Zwei-Marker-System, Keepalive, Auto-Reconnect.
 */
public class BotInstance {

    private static final Logger log = LoggerFactory.getLogger(BotInstance.class);

    private static final int BOT_ABSENT_THRESHOLD = 2;
    private static final int CONFIRM_TICKS = 2;
    private static final int AUDIO_STALL_INTERVALS = 8;

    /**
     * Gemeinsamer Watchdog-Thread fuer alle Bot-Instanzen: prueft, ob ein
     * Bot-Thread eingefroren ist (haengender Playwright-Aufruf / totes Chromium),
     * und erzwingt dann die Beendigung. Laeuft bewusst NICHT auf dem jeweiligen
     * Bot-Executor, damit er von einem Haenger dort nicht selbst blockiert wird.
     */
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bot-watchdog");
                t.setDaemon(true);
                return t;
            });
    private static final long WATCHDOG_CHECK_MS = 30_000;

    private final UUID sessionId;
    private final String meetingUrl;
    private final String botName;
    private final UUID ownerId;
    private final boolean autoRecord;
    private final boolean recordVideo;
    private final boolean aiAnalysis;
    private final boolean diarize;
    private final BotConfig config;
    private final AppProperties.Bots botProps;
    private final RecordingService recordingService;
    private final BotSessionRepo sessionRepo;
    private final Runnable onTerminated;

    private final ScheduledExecutorService executor;
    private final SessionMarkers markers = new SessionMarkers();

    // Playwright-Stack (nur auf dem Bot-Thread anfassen)
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private ChatOps chat;
    private ParticipantOps participants;
    private PageAudioRecorder recorder;
    private final BbbJoiner joiner = new BbbJoiner();

    private ScheduledFuture<?> monitorTask;
    private ScheduledFuture<?> keepaliveTask;
    private ScheduledFuture<?> watchdogTask;

    /** Zeitstempel des letzten Lebenszeichens des Bot-Threads (fuer den Watchdog). */
    private volatile long lastHeartbeatMs;
    /** Maximal erlaubte Zeit ohne Lebenszeichen, bevor der Watchdog zuschlaegt. */
    private final long watchdogTimeoutMs;
    /** Verhindert doppelte Beendigung (normaler Abbau vs. Watchdog). */
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    // Aufnahme-Zustand (nur Bot-Thread)
    private UUID currentRecordingId;
    // Aufnahme, der das Video des aktuellen Browser-Kontextes zugeordnet wird
    // (wird beim Kontext-Close gemuxt). Nur im Video-Modus gesetzt.
    private UUID videoRecordingId;
    // Wall-Clock-Start (epoch ms) der Playwright-Video-/Kontextaufnahme des aktuellen
    // Kontextes - fuer die A/V-Synchronisierung beim Muxen.
    private long videoStartEpochMs;
    private RecordingSegment currentSegment;
    private OutputStream segmentOut;
    private int segmentSeq;
    private boolean stoppingRecording;
    private boolean lastChunkReceived;
    private final StringBuilder participantsLog = new StringBuilder();
    private final Set<String> knownParticipants = new LinkedHashSet<>();

    // Monitor-Zaehler (nur Bot-Thread)
    private int startConfirmTicks;
    private int stopConfirmTicks;
    private int botAbsentCount;
    private int reconnectAttempts;
    private String lastProcessedStartSignature;
    private volatile long lastChunkAtMs;

    // Sichtbarer Zustand fuer REST/Frontend
    private volatile BotSession.Status status = BotSession.Status.STARTING;
    // Aus der BBB-Oberflaeche erkannter Raumname (nach Join, ggf. verzoegert)
    private volatile String roomName;
    private volatile int currentOthers;
    private volatile int currentAudioTracks;
    private volatile String lastError;
    private volatile boolean shuttingDown;
    // Unterdrueckt den automatischen Neustart der Aufnahme nach einem MANUELLEN
    // Stopp (Frontend-Button oder Chat-STOP). Wird durch einen manuellen/Chat-Start
    // wieder aufgehoben. Auto-Stopp (keine Teilnehmer) setzt dies NICHT.
    private volatile boolean autoRecordSuppressed;

    public BotInstance(BotSession session, BotConfig config, AppProperties.Bots botProps,
                       RecordingService recordingService, BotSessionRepo sessionRepo, Runnable onTerminated) {
        this.sessionId = session.getId();
        this.meetingUrl = session.getMeetingUrl();
        this.botName = session.getBotName();
        this.ownerId = session.getCreatedBy();
        this.autoRecord = session.isAutoRecord();
        this.recordVideo = session.isRecordVideo();
        this.aiAnalysis = session.isAiAnalysis();
        this.diarize = session.isDiarize();
        this.config = config;
        this.botProps = botProps;
        this.recordingService = recordingService;
        this.sessionRepo = sessionRepo;
        this.onTerminated = onTerminated;
        // Grosszuegiger Puffer ueber den laengsten legitimen Blockier-Vorgang
        // (Join + Warten auf Audio) hinaus, damit der Watchdog nicht faelschlich
        // waehrend eines langsamen, aber gesunden Joins zuschlaegt.
        this.watchdogTimeoutMs = botProps.getJoinTimeoutMs() + botProps.getAudioReadyTimeoutMs() + 120_000;
        String shortId = sessionId.toString().substring(0, 8);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                MDC.put("bot", shortId);
                r.run();
            }, "bot-" + shortId);
            t.setDaemon(true);
            return t;
        });
    }

    // ---------------------------------------------------------------- API

    public void startAsync() {
        heartbeat();
        watchdogTask = WATCHDOG.scheduleWithFixedDelay(this::watchdogCheck,
                WATCHDOG_CHECK_MS, WATCHDOG_CHECK_MS, TimeUnit.MILLISECONDS);
        executor.execute(this::doStart);
    }

    /** Vom Bot-Thread aufgerufen, um dem Watchdog "ich lebe" zu signalisieren. */
    private void heartbeat() {
        lastHeartbeatMs = System.currentTimeMillis();
    }

    private void watchdogCheck() {
        if (terminated.get()) return;
        long idle = System.currentTimeMillis() - lastHeartbeatMs;
        if (idle > watchdogTimeoutMs) {
            forceTerminate("Watchdog: Bot-Thread reagiert seit " + (idle / 1000)
                    + "s nicht (eingefrorenes Chromium?)");
        }
    }

    /**
     * Erzwingt die Beendigung eines eingefrorenen Bots vom Watchdog-Thread aus:
     * killt Browser/Playwright-Treiber (entblockt so den haengenden Aufruf),
     * bricht den Bot-Executor ab und meldet den Bot als beendet. Nur im bereits
     * defekten Zustand aktiv.
     */
    private void forceTerminate(String reason) {
        if (!terminated.compareAndSet(false, true)) return;
        log.error("Bot {} wird zwangsweise beendet: {}", botName, reason);
        shuttingDown = true;
        if (watchdogTask != null) watchdogTask.cancel(false);
        try { if (context != null) context.close(); } catch (RuntimeException ignored) {}
        try { if (browser != null) browser.close(); } catch (RuntimeException ignored) {}
        try { if (playwright != null) playwright.close(); } catch (RuntimeException ignored) {}
        updateStatus(BotSession.Status.FAILED, reason);
        try {
            sessionRepo.findById(sessionId).ifPresent(s -> {
                s.setEndedAt(Instant.now());
                sessionRepo.save(s);
            });
        } catch (RuntimeException ignored) {}
        executor.shutdownNow();
        try { onTerminated.run(); } catch (RuntimeException ignored) {}
    }

    public void requestRecordingStart() {
        executor.execute(() -> {
            // Manueller Start: Auto-Restart-Sperre aufheben.
            autoRecordSuppressed = false;
            if (status == BotSession.Status.JOINED && currentRecordingId == null) {
                startRecording("Frontend");
            }
        });
    }

    public void requestRecordingStop(boolean discard) {
        executor.execute(() -> {
            // Manueller Stopp: kein automatischer Neustart, bis wieder manuell/per
            // Chat gestartet wird.
            autoRecordSuppressed = true;
            if (currentRecordingId != null) {
                stopRecording(discard, discard ? "Frontend (verwerfen)" : "Frontend");
            }
        });
    }

    public void shutdownAsync() {
        shuttingDown = true;
        executor.execute(() -> {
            try {
                if (currentRecordingId != null) {
                    stopRecording(false, "Bot wird beendet");
                }
            } finally {
                terminate(BotSession.Status.STOPPED, null);
            }
        });
    }

    public UUID getSessionId() { return sessionId; }
    public BotSession.Status getStatus() { return status; }
    public UUID getCurrentRecordingId() { return currentRecordingId; }
    public int getCurrentOthers() { return currentOthers; }
    public int getCurrentAudioTracks() { return currentAudioTracks; }
    public String getLastError() { return lastError; }
    public String getMeetingUrl() { return meetingUrl; }
    public String getBotName() { return botName; }
    public String getRoomName() { return roomName; }
    public UUID getOwnerId() { return ownerId; }

    // ---------------------------------------------------------- Lebenszyklus

    private void doStart() {
        heartbeat();
        try {
            launchBrowserAndJoin();
            reconnectAttempts = 0;
            updateStatus(BotSession.Status.JOINED, null);
            detectRoomName();
            startMonitor();
            startKeepalive();
            log.info("Bot {} ist dem Raum beigetreten.", botName);
        } catch (RuntimeException e) {
            log.error("Join fehlgeschlagen: {}", e.getMessage());
            if (config.autoReconnect()) {
                scheduleReconnect("Join fehlgeschlagen: " + e.getMessage());
            } else {
                terminate(BotSession.Status.FAILED, e.getMessage());
            }
        }
    }

    private void launchBrowserAndJoin() {
        playwright = Playwright.create();
        List<String> args = new ArrayList<>(List.of(
                "--autoplay-policy=no-user-gesture-required",
                "--no-sandbox",
                "--disable-features=AudioServiceOutOfProcess",
                "--disable-dev-shm-usage",
                "--use-fake-ui-for-media-stream",
                // Fake-Mikrofon (Stille): noetig, damit der Mikrofon-Fallback der
                // Audio-Auswahl auch headless/ohne Audiogeraet funktioniert.
                "--use-fake-device-for-media-stream"
        ));
        if (botProps.isInsecureTls()) {
            args.add("--ignore-certificate-errors");
            args.add("--allow-insecure-localhost");
        }
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions()
                .setHeadless(botProps.isHeadless())
                .setArgs(args);
        if (botProps.getChromePath() != null && !botProps.getChromePath().isBlank()) {
            launch.setExecutablePath(Path.of(botProps.getChromePath()));
        }
        browser = playwright.chromium().launch(launch);
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setPermissions(List.of("microphone"));
        if (recordVideo) {
            // Playwright zeichnet die gesamte Kontext-Lebensdauer als VP8-WebM auf;
            // das fertige Video wird beim Kontext-Close eingesammelt und gemuxt.
            Path videoDir = recordingService.videoTmpDir(sessionId);
            contextOptions.setViewportSize(1280, 720)
                    .setRecordVideoDir(videoDir)
                    .setRecordVideoSize(1280, 720);
        }
        context = browser.newContext(contextOptions);
        page = context.newPage();
        // Ab hier laeuft die Kontext-/Videoaufnahme; Zeitpunkt fuer die A/V-Sync merken.
        videoStartEpochMs = System.currentTimeMillis();
        chat = new ChatOps(page);
        participants = new ParticipantOps(page);
        recorder = new PageAudioRecorder(page);

        joiner.join(page, meetingUrl, botName, botProps.getJoinTimeoutMs(), botProps.getAudioReadyTimeoutMs());
    }

    /**
     * Liest den Raumnamen aus der BBB-Oberflaeche (Navbar-Titel bzw.
     * Dokumenttitel) und persistiert ihn an der Session. Best-effort - wenn der
     * Name noch nicht geladen ist, versucht es der Monitor-Tick erneut.
     */
    private void detectRoomName() {
        if (roomName != null && !roomName.isBlank()) return;
        try {
            Object result = page.evaluate(BrowserScripts.load(BrowserScripts.ROOM_NAME));
            String name = result == null ? "" : result.toString().trim();
            if (name.isEmpty()) return;
            roomName = name;
            sessionRepo.findById(sessionId).ifPresent(s -> {
                s.setRoomName(name);
                sessionRepo.save(s);
            });
            log.info("Raumname erkannt: '{}'", name);
        } catch (RuntimeException e) {
            log.debug("Raumname konnte nicht ermittelt werden: {}", e.getMessage());
        }
    }

    private void startMonitor() {
        if (monitorTask != null) monitorTask.cancel(false);
        monitorTask = executor.scheduleWithFixedDelay(this::monitorTick,
                config.checkIntervalMs(), config.checkIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void startKeepalive() {
        if (!config.keepaliveEnabled()) return;
        if (keepaliveTask != null) keepaliveTask.cancel(false);
        keepaliveTask = executor.scheduleWithFixedDelay(() -> {
            if (status != BotSession.Status.JOINED && status != BotSession.Status.RECORDING) return;
            try {
                chat.sendMessage(config.keepalivePrefix() + " " + config.keepaliveMessage());
            } catch (RuntimeException e) {
                log.debug("Keepalive fehlgeschlagen: {}", e.getMessage());
            }
        }, config.keepaliveIntervalMs(), config.keepaliveIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void terminate(BotSession.Status finalStatus, String error) {
        if (!terminated.compareAndSet(false, true)) return;
        if (watchdogTask != null) watchdogTask.cancel(false);
        closeBrowserQuietly();
        updateStatus(finalStatus, error);
        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setEndedAt(Instant.now());
            sessionRepo.save(s);
        });
        executor.shutdown();
        onTerminated.run();
        log.info("Bot beendet (Status {}).", finalStatus);
    }

    private void closeBrowserQuietly() {
        // Video-Handle vor dem Schliessen greifen; die Datei wird erst beim
        // Kontext-Close geschrieben, der Pfad ist danach abrufbar.
        com.microsoft.playwright.Video video = null;
        if (recordVideo && page != null) {
            try { video = page.video(); } catch (RuntimeException ignored) {}
        }
        try { if (page != null) page.close(); } catch (RuntimeException ignored) {}
        try { if (context != null) context.close(); } catch (RuntimeException ignored) {}
        Path videoPath = null;
        if (video != null) {
            try { videoPath = video.path(); } catch (RuntimeException e) {
                log.warn("Video-Pfad konnte nicht ermittelt werden: {}", e.getMessage());
            }
        }
        try { if (browser != null) browser.close(); } catch (RuntimeException ignored) {}
        try { if (playwright != null) playwright.close(); } catch (RuntimeException ignored) {}
        page = null; context = null; browser = null; playwright = null;

        if (videoPath != null) {
            if (videoRecordingId != null) {
                recordingService.attachVideo(videoRecordingId, List.of(videoPath), videoStartEpochMs);
            } else {
                // Kein Aufnahme-Bezug (z.B. Bot joint, niemand kommt) -> Video verwerfen
                try { Files.deleteIfExists(videoPath); } catch (IOException ignored) {}
            }
        }
        videoRecordingId = null;
    }

    private void updateStatus(BotSession.Status newStatus, String error) {
        this.status = newStatus;
        this.lastError = error;
        try {
            sessionRepo.findById(sessionId).ifPresent(s -> {
                s.setStatus(newStatus);
                if (error != null) s.setLastError(error);
                sessionRepo.save(s);
            });
        } catch (RuntimeException e) {
            log.warn("Status-Persistierung fehlgeschlagen: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------- Reconnect

    private void scheduleReconnect(String reason) {
        if (shuttingDown) return;
        if (!config.autoReconnect()) {
            terminate(BotSession.Status.FAILED, reason);
            return;
        }
        reconnectAttempts++;
        if (config.reconnectMaxAttempts() >= 0 && reconnectAttempts > config.reconnectMaxAttempts()) {
            terminate(BotSession.Status.FAILED, "Reconnect-Limit erreicht: " + reason);
            return;
        }
        updateStatus(BotSession.Status.RECONNECTING, reason);

        // Laufende Aufnahme best-effort finalisieren (Segmente liegen auf Platte)
        if (currentRecordingId != null) {
            try {
                stopRecording(false, "Reconnect: " + reason);
            } catch (RuntimeException e) {
                log.warn("Finalisierung vor Reconnect fehlgeschlagen: {}", e.getMessage());
                forceCloseRecordingState();
            }
        }
        closeBrowserQuietly();
        if (recorder != null) recorder.resetBindingState();

        long delay = (long) (config.reconnectBackoffBaseMs()
                * Math.pow(config.reconnectBackoffFactor(), Math.min(reconnectAttempts - 1, 10)));
        log.warn("Reconnect-Versuch {} in {} ms (Grund: {})", reconnectAttempts, delay, reason);
        executor.schedule(this::doStart, delay, TimeUnit.MILLISECONDS);
    }

    /** Notfall-Aufraeumen, wenn der Browser bereits tot ist. */
    private void forceCloseRecordingState() {
        try { if (segmentOut != null) segmentOut.close(); } catch (IOException ignored) {}
        if (currentSegment != null) recordingService.segmentFinished(currentSegment);
        UUID recId = currentRecordingId;
        String pLog = participantsLog.toString();
        clearRecordingState();
        if (recId != null) {
            recordingService.finalizeRecording(recId, pLog, "", false, "Browser-Verbindung verloren");
        }
    }

    // ------------------------------------------------------------ Monitoring

    private void monitorTick() {
        heartbeat();
        if (shuttingDown || status == BotSession.Status.RECONNECTING || page == null) return;
        try {
            // Verzoegert erscheinende Info-Fenster (z.B. "Session details") wegklicken,
            // damit sie im aufgenommenen Video die geteilte Ansicht nicht verdecken.
            try { joiner.dismissModals(page); } catch (RuntimeException ignored) {}

            // Raumname nachtragen, falls er beim Join noch nicht geladen war
            detectRoomName();

            ParticipantOps.AttendeeInfo info = participants.getAttendeeInfo(botName);
            currentOthers = info.others();
            int tracks = participants.remoteAudioTrackCount();
            currentAudioTracks = tracks;

            // Bot-Praesenz-Check: fliegt der Bot aus dem Raum -> Reconnect
            if (info.total() > 0 && !participants.isBotPresent(botName, info)) {
                botAbsentCount++;
                if (botAbsentCount >= BOT_ABSENT_THRESHOLD) {
                    botAbsentCount = 0;
                    // Diagnose: erkannte Namen mitloggen, damit sichtbar wird, ob der
                    // Bot-Eintrag vom Scraper (attendees.js) einfach nicht erfasst wird.
                    scheduleReconnect("Bot nicht mehr in Teilnehmerliste (Bot='" + botName
                            + "', erkannt=" + info.names() + ")");
                    return;
                }
            } else {
                botAbsentCount = 0;
            }

            if (currentRecordingId != null) {
                tickWhileRecording(info);
            } else if (status == BotSession.Status.JOINED) {
                tickWhileIdle(info, tracks);
            }
        } catch (RuntimeException e) {
            log.warn("Monitor-Tick fehlgeschlagen: {}", e.getMessage());
        }
    }

    private void tickWhileRecording(ParticipantOps.AttendeeInfo info) {
        // Audio-Stall: keine Chunks mehr -> Verbindung vermutlich tot
        long stallThreshold = AUDIO_STALL_INTERVALS * config.checkIntervalMs();
        if (lastChunkAtMs > 0 && System.currentTimeMillis() - lastChunkAtMs > stallThreshold) {
            scheduleReconnect("Audio-Stall (keine Daten seit " + stallThreshold + " ms)");
            return;
        }

        // Neue Teilnehmer protokollieren
        for (String name : info.names()) {
            if (!NameUtils.isNameLikeBot(name, botName) && knownParticipants.add(name)) {
                participantsLog.append("+ ").append(Instant.now()).append(" JOINED: ").append(name).append('\n');
            }
        }

        // Chat-Befehle nach aktivem Marker (STOP verwirft die Aufnahme)
        if (markers.hasActive()) {
            CommandDetector.Detection det = CommandDetector.detectAfterMarker(
                    chat.getAllChatText(), markers.getActive(),
                    config.chatStopCommand(), config.chatStartCommand());
            if (det.markerFound() && det.foundStop()) {
                handleChatStop();
                return;
            }
        }

        // Keine Teilnehmer mehr -> Aufnahme beenden (mit Doppel-Bestaetigung)
        if (info.others() == 0) {
            stopConfirmTicks++;
            if (stopConfirmTicks >= CONFIRM_TICKS) {
                stopConfirmTicks = 0;
                stopRecording(false, "Keine Teilnehmer mehr im Raum");
            }
        } else {
            stopConfirmTicks = 0;
        }
    }

    private void tickWhileIdle(ParticipantOps.AttendeeInfo info, int tracks) {
        boolean enoughParticipants = info.others() >= config.recordMinOthers();
        boolean hasAudio = tracks > 0;

        // Chat-START-Befehl: nach Cutoff-Marker suchen (Selbst-Trigger-Schutz),
        // sonst im Gesamt-Chat mit Debounce ueber die Nachrichten-Signatur
        boolean chatStart = false;
        if (markers.hasCutoff()) {
            String after = CommandDetector.textAfterMarker(chat.getAllChatText(), markers.getCutoff());
            // Bot-eigene Hinweismeldungen (mit Marker) ausblenden -> kein Selbst-Trigger.
            chatStart = after != null
                    && CommandDetector.containsCommand(CommandDetector.stripBotMarkerLines(after), config.chatStartCommand());
        } else {
            ChatOps.StartCommandInfo cmdInfo = chat.detectStartCommandWithInfo(config.chatStartCommand());
            if (cmdInfo.found()) {
                String signature = cmdInfo.timestamp() + "|" + cmdInfo.messagePreview();
                if (!signature.equals(lastProcessedStartSignature)) {
                    lastProcessedStartSignature = signature;
                    chatStart = true;
                }
            }
        }
        if (chatStart) {
            // Chat-Start hebt eine vorherige manuelle Stopp-Sperre auf.
            autoRecordSuppressed = false;
            if (enoughParticipants && hasAudio) {
                log.info("START-Befehl im Chat erkannt - starte Aufnahme.");
                startRecording("Chat-Befehl");
            } else {
                log.info("START-Befehl erkannt, aber Bedingungen nicht erfuellt (Teilnehmer: {}, Tracks: {}).",
                        info.others(), tracks);
                try {
                    chat.sendMessage("Aufnahme kann nicht starten: zu wenige Teilnehmer oder kein Audio.");
                } catch (RuntimeException ignored) {}
            }
            return;
        }

        // Automatischer Start nach Teilnehmerzahl (mit Doppel-Bestaetigung).
        // Nach einem manuellen Stopp (autoRecordSuppressed) NICHT neu starten.
        if (autoRecord && !autoRecordSuppressed && enoughParticipants && hasAudio) {
            startConfirmTicks++;
            if (startConfirmTicks >= CONFIRM_TICKS) {
                startConfirmTicks = 0;
                startRecording("Automatisch (Teilnehmer anwesend)");
            }
        } else {
            startConfirmTicks = 0;
        }
    }

    // -------------------------------------------------------------- Aufnahme

    private void startRecording(String trigger) {
        try {
            // Erkannter Raumname wird Titel der Aufnahme (Uebersicht zeigt dann
            // den Namen statt der Meeting-URL).
            Recording recording = recordingService.createRecording(sessionId, ownerId, meetingUrl,
                    recordVideo, aiAnalysis, diarize, roomName);
            currentRecordingId = recording.getId();
            if (recordVideo) {
                // Video des laufenden Kontextes dieser Aufnahme zuordnen.
                videoRecordingId = recording.getId();
            }
            segmentSeq = 0;
            stoppingRecording = false;
            lastChunkReceived = false;
            lastChunkAtMs = System.currentTimeMillis();
            openSegmentFile(recording.getDirectory());

            participantsLog.setLength(0);
            knownParticipants.clear();
            ParticipantOps.AttendeeInfo info = participants.getAttendeeInfo(botName);
            participantsLog.append("Aufnahme: ").append(recording.getId()).append('\n')
                    .append("Start (UTC): ").append(Instant.now()).append('\n')
                    .append("Bot: ").append(botName).append('\n')
                    .append("Teilnehmer zu Beginn:\n");
            for (String name : info.names()) {
                if (!NameUtils.isNameLikeBot(name, botName)) {
                    knownParticipants.add(name);
                    participantsLog.append("  - ").append(name).append('\n');
                }
            }

            String marker = SessionMarkers.generate();
            markers.setActive(marker);
            markers.clearCutoff();

            long segmentMs = config.segmentMinutes() * 60_000L;
            recorder.start(segmentMs, this::onAudioChunk);

            if (config.sendChatWarning()) {
                try {
                    chat.sendMessage(config.buildWarnMessage(marker));
                } catch (RuntimeException e) {
                    log.warn("Warnmeldung konnte nicht gesendet werden: {}", e.getMessage());
                }
            }

            updateStatus(BotSession.Status.RECORDING, null);
            log.info("Aufnahme {} gestartet (Ausloeser: {}).", currentRecordingId, trigger);
        } catch (RuntimeException e) {
            log.error("Aufnahmestart fehlgeschlagen: {}", e.getMessage());
            clearRecordingState();
        }
    }

    private void handleChatStop() {
        log.info("STOP-Befehl im Chat erkannt - Aufnahme wird verworfen.");
        // Teilnehmer hat bewusst gestoppt: keinen automatischen Neustart ausloesen.
        autoRecordSuppressed = true;
        String cutoff = SessionMarkers.generate();
        try {
            chat.sendMessage("Aufzeichnung wurde verworfen. Mit " + config.chatStartCommand()
                    + " kann eine neue Aufzeichnung gestartet werden. [" + cutoff + "]");
        } catch (RuntimeException e) {
            log.warn("Stop-Bestaetigung konnte nicht gesendet werden: {}", e.getMessage());
        }
        markers.setCutoff(cutoff);
        stopRecording(true, "Chat-STOP durch Teilnehmer");
    }

    private void stopRecording(boolean discard, String reason) {
        if (currentRecordingId == null) return;
        UUID recId = currentRecordingId;
        log.info("Stoppe Aufnahme {} ({}, verwerfen={}).", recId, reason, discard);
        stoppingRecording = true;
        try {
            recorder.stop();
            // Auf letzten Chunk warten; page.waitForTimeout pumpt dabei die
            // Playwright-Events, ueber die die Chunks ankommen.
            long deadline = System.currentTimeMillis() + 10_000;
            while (!lastChunkReceived && System.currentTimeMillis() < deadline) {
                try {
                    page.waitForTimeout(200);
                } catch (RuntimeException e) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Recorder-Stopp fehlgeschlagen: {}", e.getMessage());
        }
        recorder.clearSink();

        try { if (segmentOut != null) segmentOut.close(); } catch (IOException ignored) {}
        segmentOut = null;
        if (currentSegment != null) {
            recordingService.segmentFinished(currentSegment);
            currentSegment = null;
        }

        String chatLog = "";
        if (!discard) {
            try {
                chatLog = chat.getChatSinceMarker(markers.getActive(), 3, 250, config.keepalivePrefix());
            } catch (RuntimeException e) {
                log.warn("Chat-Extraktion fehlgeschlagen: {}", e.getMessage());
            }
        }
        participantsLog.append("Ende (UTC): ").append(Instant.now()).append(" - ").append(reason).append('\n');
        String pLog = participantsLog.toString();

        // Cutoff-Marker setzen, damit ein spaeterer START-Scan nicht auf alte
        // Nachrichten (oder Bot-eigene Hinweise) anspringt
        if (!discard && config.sendChatWarning() && !markers.hasCutoff()) {
            String cutoff = SessionMarkers.generate();
            try {
                chat.sendMessage("Aufzeichnung beendet. Mit " + config.chatStartCommand()
                        + " kann eine neue Aufzeichnung gestartet werden. [" + cutoff + "]");
                markers.setCutoff(cutoff);
            } catch (RuntimeException e) {
                log.debug("Cutoff-Nachricht konnte nicht gesendet werden: {}", e.getMessage());
            }
        }

        clearRecordingState();
        recordingService.finalizeRecording(recId, pLog, chatLog, discard, reason);
        if (!shuttingDown && status == BotSession.Status.RECORDING) {
            updateStatus(BotSession.Status.JOINED, null);
        }
    }

    private void clearRecordingState() {
        currentRecordingId = null;
        currentSegment = null;
        try { if (segmentOut != null) segmentOut.close(); } catch (IOException ignored) {}
        segmentOut = null;
        stoppingRecording = false;
        markers.clearActive();
        lastProcessedStartSignature = null;
        lastChunkAtMs = 0;
    }

    private void openSegmentFile(String directory) {
        try {
            Path webm = Path.of(directory).resolve("segment_%03d.webm".formatted(segmentSeq));
            segmentOut = Files.newOutputStream(webm, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentSegment = recordingService.registerSegment(currentRecordingId, segmentSeq, webm);
        } catch (IOException e) {
            throw new IllegalStateException("Segmentdatei kann nicht geoeffnet werden", e);
        }
    }

    /** Chunk-Callback aus dem Browser (laeuft auf dem Bot-Thread waehrend Playwright-Dispatch). */
    private void onAudioChunk(byte[] data, boolean isLast) {
        lastChunkAtMs = System.currentTimeMillis();
        try {
            if (segmentOut != null && data.length > 0) {
                segmentOut.write(data);
            }
            if (isLast) {
                if (stoppingRecording) {
                    lastChunkReceived = true;
                    return;
                }
                // Segment-Rotation: aktuelles Segment abschliessen, naechstes oeffnen
                if (segmentOut != null) segmentOut.close();
                segmentOut = null;
                if (currentSegment != null) {
                    recordingService.segmentFinished(currentSegment);
                }
                currentSegment = null;
                segmentSeq++;
                if (currentRecordingId != null && !rotateToNextSegment()) {
                    // Konnte kein neues Segment oeffnen -> nicht still alle weiteren
                    // Chunks verwerfen, sondern die Aufnahme kontrolliert beenden,
                    // damit das bereits Aufgenommene finalisiert wird.
                    failRecordingAfterRotation();
                }
            }
        } catch (IOException e) {
            log.error("Audio-Chunk konnte nicht geschrieben werden: {}", e.getMessage());
        }
    }

    /** Oeffnet die naechste Segmentdatei nach einer Rotation. false = fehlgeschlagen. */
    private boolean rotateToNextSegment() {
        var dir = recordingService.findDirectory(currentRecordingId);
        if (dir.isEmpty()) {
            log.error("Aufnahme-Verzeichnis fuer {} nicht gefunden - Segment-Rotation nicht moeglich",
                    currentRecordingId);
            return false;
        }
        try {
            openSegmentFile(dir.get());
            return segmentOut != null;
        } catch (RuntimeException e) {
            log.error("Naechstes Segment konnte nicht geoeffnet werden: {}", e.getMessage());
            return false;
        }
    }

    /** Beendet die Aufnahme nach einem Rotationsfehler kontrolliert (auf dem Bot-Thread). */
    private void failRecordingAfterRotation() {
        String reason = "Segment-Rotation fehlgeschlagen - Aufnahme wird beendet";
        this.lastError = reason;
        // Deferred: vermeidet Re-Entrancy waehrend des laufenden Chunk-Dispatches.
        executor.execute(() -> {
            if (currentRecordingId != null && !stoppingRecording) {
                stopRecording(false, reason);
            }
        });
    }
}
