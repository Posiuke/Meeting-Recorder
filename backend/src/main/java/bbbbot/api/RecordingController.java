package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.bot.BotManager;
import bbbbot.domain.AppUser;
import bbbbot.domain.Participant;
import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.ShareGrant;
import bbbbot.domain.ShareLink;
import bbbbot.domain.Summary;
import bbbbot.domain.UserGroup;
import bbbbot.llm.SummaryService;
import bbbbot.processing.ProcessingService;
import bbbbot.recording.ParticipantService;
import bbbbot.recording.RecordingService;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.ParticipantRepo;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.ShareGrantRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import bbbbot.repository.Repositories.UserGroupRepo;
import bbbbot.sharing.AccessService;
import bbbbot.sharing.ShareLinkService;
import bbbbot.stt.TranscriptAssembler;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/recordings")
public class RecordingController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RecordingController.class);

    /** Konfiguriertes Upload-Limit - wird dem Frontend fuer die Vorab-Pruefung mitgeteilt. */
    @org.springframework.beans.factory.annotation.Value("${spring.servlet.multipart.max-file-size:4GB}")
    private org.springframework.util.unit.DataSize maxUploadSize;

    private final AccessService access;
    private final RecordingRepo recordingRepo;
    private final RecordingSegmentRepo segmentRepo;
    private final SummaryRepo summaryRepo;
    private final ProcessingJobRepo jobRepo;
    private final ShareGrantRepo shareRepo;
    private final AppUserRepo userRepo;
    private final UserGroupRepo groupRepo;
    private final ProcessingService processingService;
    private final BotManager botManager;
    private final RecordingService recordingService;
    private final bbbbot.recording.CaptureService captureService;
    private final bbbbot.recording.RecordingSearch recordingSearch;
    private final bbbbot.recording.RecordingTagService tagService;
    private final ParticipantService participantService;
    private final ParticipantRepo participantRepo;
    private final SummaryService summaryService;
    private final bbbbot.settings.SettingsService settings;
    private final ShareLinkService shareLinkService;
    private final bbbbot.recording.RecordingMediaService media;

    public RecordingController(AccessService access, RecordingRepo recordingRepo,
                               RecordingSegmentRepo segmentRepo, SummaryRepo summaryRepo,
                               ProcessingJobRepo jobRepo, ShareGrantRepo shareRepo,
                               AppUserRepo userRepo, UserGroupRepo groupRepo,
                               ProcessingService processingService, BotManager botManager,
                               RecordingService recordingService,
                               bbbbot.recording.CaptureService captureService,
                               bbbbot.recording.RecordingSearch recordingSearch,
                               bbbbot.recording.RecordingTagService tagService,
                               ParticipantService participantService,
                               ParticipantRepo participantRepo, SummaryService summaryService,
                               bbbbot.settings.SettingsService settings,
                               ShareLinkService shareLinkService,
                               bbbbot.recording.RecordingMediaService media) {
        this.access = access;
        this.recordingRepo = recordingRepo;
        this.segmentRepo = segmentRepo;
        this.summaryRepo = summaryRepo;
        this.jobRepo = jobRepo;
        this.shareRepo = shareRepo;
        this.userRepo = userRepo;
        this.groupRepo = groupRepo;
        this.processingService = processingService;
        this.botManager = botManager;
        this.recordingService = recordingService;
        this.captureService = captureService;
        this.recordingSearch = recordingSearch;
        this.tagService = tagService;
        this.participantService = participantService;
        this.participantRepo = participantRepo;
        this.summaryService = summaryService;
        this.settings = settings;
        this.shareLinkService = shareLinkService;
        this.media = media;
    }

    // ---------------------------------------------------------------- Upload

    /**
     * Unterstuetzte Endungen fuer den Upload; ffmpeg extrahiert aus Video-Containern
     * die Tonspur. Oeffentlich, weil die API-Transkription
     * ({@link TranscriptionController}) dieselben Formate annimmt.
     */
    public static final Set<String> UPLOAD_EXTENSIONS = Set.of(
            "mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "wma", "amr",
            "webm", "mka", "mp4", "mkv", "mov", "avi", "3gp", "ts");

    /** Laengengrenze des Auswertungs-Prompts - gleich beim Upload und pro Aufnahme. */
    private static final int MAX_SUMMARY_PROMPT_LENGTH = 8000;

    /**
     * Normalisiert einen Auswertungs-Prompt: leer bedeutet "Admin-Standard
     * verwenden" (null), zu lang wird abgelehnt.
     */
    private static String requireSummaryPrompt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String prompt = raw.trim();
        if (prompt.length() > MAX_SUMMARY_PROMPT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Auswertungs-Prompt ist zu lang (max. " + MAX_SUMMARY_PROMPT_LENGTH + " Zeichen)");
        }
        return prompt;
    }

    /**
     * Normalisiert eine Sprachangabe fuer die Spracherkennung: leer bedeutet
     * "Admin-Standard verwenden" (null), {@code auto} laesst Whisper selbst
     * erkennen. Oeffentlich, weil auch der Bot-Start ({@link BotController}) sie
     * annimmt.
     */
    public static String requireSttLanguage(String raw) {
        try {
            return bbbbot.stt.SttLanguage.normalize(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Prueft die Dateiendung gegen {@link #UPLOAD_EXTENSIONS} und liefert den zu
     * verwendenden Dateinamen zurueck.
     */
    static String requireSupportedFilename(String originalFilename) {
        String original = originalFilename;
        if (original == null || original.isBlank()) original = "aufnahme";
        int dot = original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!UPLOAD_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Dateityp nicht unterstuetzt: ." + ext + " (erlaubt: "
                            + String.join(", ", UPLOAD_EXTENSIONS.stream().sorted().toList()) + ")");
        }
        return original;
    }

    /**
     * Upload-Rahmenbedingungen fuers Frontend: Groessenlimit (Pruefung vor dem
     * Hochladen), ob der Admin die Sprechererkennung freigeschaltet hat und
     * welche Sprache er fuer die Spracherkennung vorgibt (fuer die Beschriftung
     * der Auswahl "Standard (…)").
     */
    @GetMapping("/upload-config")
    public Map<String, Object> uploadConfig() {
        return Map.of(
                "maxFileSizeBytes", maxUploadSize.toBytes(),
                "diarizeAllowed", settings.getBool(bbbbot.settings.SettingsService.WHISPER_DIARIZE),
                "sttLanguage", settings.get(bbbbot.settings.SettingsService.WHISPER_LANGUAGE));
    }

    /**
     * Nimmt eine Audio-/Videodatei entgegen und legt daraus eine Aufnahme an.
     * Die Datei wird asynchron zu einem MP3-Segment transkodiert; bei
     * {@code aiAnalysis} wird anschliessend ein Verarbeitungs-Job angelegt
     * ({@code processNow} stuft ihn auf Sofort-Auswertung hoch).
     *
     * @param summaryPrompt Auswertungs-Prompt fuer diese Aufnahme (leer =
     *                      Admin-Standard). Damit laesst sich schon beim Hochladen
     *                      eine andere Vorlage als "Meeting" waehlen - sonst
     *                      liefe eine Sofort-Auswertung noch mit dem Standard,
     *                      bevor man sie im Nachhinein anpassen koennte.
     * @param sttLanguage   Sprache der Spracherkennung (leer = Admin-Standard,
     *                      "auto" = automatisch erkennen). Aus demselben Grund
     *                      schon hier waehlbar - ein mit falscher Sprache
     *                      erzeugtes Transkript ist nachtraeglich nicht zu retten.
     */
    @PostMapping("/upload")
    public Dtos.RecordingView upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "title", required = false) String title,
                                     @RequestParam(value = "aiAnalysis", defaultValue = "true") boolean aiAnalysis,
                                     @RequestParam(value = "processNow", defaultValue = "false") boolean processNow,
                                     @RequestParam(value = "diarize", defaultValue = "false") boolean diarize,
                                     @RequestParam(value = "summaryPrompt", required = false) String summaryPrompt,
                                     @RequestParam(value = "sttLanguage", required = false) String sttLanguage) {
        AppUser user = CurrentUser.get();
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keine Datei uebermittelt");
        }
        String original = requireSupportedFilename(file.getOriginalFilename());
        // Diarisierung nur, wenn der Admin sie freigeschaltet hat
        boolean diarizeEffective = diarize
                && settings.getBool(bbbbot.settings.SettingsService.WHISPER_DIARIZE);
        String prompt = requireSummaryPrompt(summaryPrompt);
        String language = requireSttLanguage(sttLanguage);
        try (InputStream in = file.getInputStream()) {
            var recording = recordingService.createUploadedRecording(user.getId(),
                    bbbbot.recording.RecordingService.UploadOptions.forUpload(
                            title, aiAnalysis, processNow, diarizeEffective, prompt, language),
                    original, in);
            return toView(recording, user);
        } catch (IOException e) {
            log.error("Upload '{}' konnte nicht gespeichert werden", original, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Upload konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    /**
     * Aufnahmen, die der Nutzer sehen darf - optional gefiltert.
     *
     * @param q       Suchbegriff fuer Titel/Raumname, Meeting-URL und Schlagworte
     * @param tag     nur Aufnahmen mit diesem Schlagwort
     * @param content zusaetzlich in Transkript und Zusammenfassung suchen
     */
    @GetMapping
    public List<Dtos.RecordingView> list(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String tag,
                                         @RequestParam(defaultValue = "false") boolean content) {
        AppUser user = CurrentUser.get();
        List<Recording> recordings = recordingSearch.search(user.getId(), q, tag, content);
        // Schlagworte aller Treffer in einer Abfrage, nicht pro Zeile
        Map<UUID, List<String>> tags = tagService.tagsOf(recordings);
        return recordings.stream()
                .map(r -> toView(r, user, tags.getOrDefault(r.getId(), List.of())))
                .toList();
    }

    // ------------------------------------------------------------ Schlagworte

    /**
     * Alle Schlagworte, die der Nutzer sehen kann, mit Anzahl der Aufnahmen -
     * fuer die Filterleiste und die Vorschlagsliste im Eingabefeld.
     */
    @GetMapping("/tags")
    public List<Dtos.TagCountView> tags() {
        AppUser user = CurrentUser.get();
        return tagService.visibleTags(user.getId()).stream()
                .map(t -> new Dtos.TagCountView(t.name(), t.count()))
                .toList();
    }

    /** Schlagwort setzen (nur Besitzer). Doppelte Eingaben sind kein Fehler. */
    @PostMapping("/{id}/tags")
    public List<String> addTag(@PathVariable UUID id, @RequestBody Dtos.TagRequest request) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        try {
            return tagService.addTag(id, request.name());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Schlagwort entfernen (nur Besitzer); nicht vorhandene Schlagworte sind kein Fehler. */
    @DeleteMapping("/{id}/tags")
    public List<String> removeTag(@PathVariable UUID id, @RequestParam String name) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        return tagService.removeTag(id, name);
    }

    @GetMapping("/{id}")
    public Dtos.RecordingDetail detail(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireReadable(id, user);
        List<Dtos.SegmentView> segments = segmentRepo.findByRecordingIdOrderBySeq(id).stream()
                .map(Dtos.SegmentView::of).toList();
        List<Dtos.SummaryView> summaries = summaryRepo.findByRecordingIdOrderByCreatedAtDesc(id).stream()
                .map(Dtos.SummaryView::of).toList();
        List<Dtos.JobView> jobs = jobRepo.findByRecordingIdOrderByCreatedAtDesc(id).stream()
                .map(Dtos.JobView::of).toList();
        List<Dtos.ParticipantView> participants = participantService.ensureFromTranscript(recording).stream()
                .map(Dtos.ParticipantView::of).toList();
        return new Dtos.RecordingDetail(toView(recording, user), segments, summaries, jobs, participants,
                recording.getParticipantsLog(), recording.getChatLog(), summaryOptionsView(recording));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireOwner(id, user);
        // Nur blockieren, wenn wirklich noch aufgenommen wird (Bot oder Bildschirmaufnahme
        // im Browser). Haengengebliebene (korrupte) Aufnahmen duerfen geloescht werden.
        boolean stuck = recording.getStatus() == Recording.Status.RECORDING
                || recording.getStatus() == Recording.Status.FINALIZING;
        if (stuck && isCapturing(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aufnahme laeuft noch");
        }
        deleteDirectory(Path.of(recording.getDirectory()));
        recordingRepo.delete(recording);
    }

    /**
     * Raeumt haengengebliebene (korrupte) Aufnahmen des angemeldeten Nutzers auf:
     * Status RECORDING/FINALIZING ohne aktiven Bot - typischerweise Reste abgestuerzter
     * oder fehlgeschlagener Laeufe.
     */
    @PostMapping("/cleanup-corrupt")
    public Map<String, Integer> cleanupCorrupt() {
        AppUser user = CurrentUser.get();
        int deleted = 0;
        for (Recording recording : recordingRepo.findByOwnerIdOrderByStartedAtDesc(user.getId())) {
            boolean stuck = recording.getStatus() == Recording.Status.RECORDING
                    || recording.getStatus() == Recording.Status.FINALIZING;
            if (stuck && !isCapturing(recording.getId())) {
                deleteDirectory(Path.of(recording.getDirectory()));
                recordingRepo.delete(recording);
                deleted++;
            }
        }
        return Map.of("deleted", deleted);
    }

    // ------------------------------------------------------------ Audio/Text

    @GetMapping("/{id}/segments/{segmentId}/audio")
    public ResponseEntity<FileSystemResource> audio(@PathVariable UUID id, @PathVariable UUID segmentId) {
        AppUser user = CurrentUser.get();
        access.requireReadable(id, user);
        return media.audio(id, segmentId);
    }

    /**
     * Durchgehende Tonspur der ganzen Aufnahme (Segmente zusammengefuegt) -
     * fuer den Player im Transkript, aus dem sich per Klick auf eine Zeile an
     * die passende Stelle springen laesst.
     */
    @GetMapping("/{id}/audio")
    public ResponseEntity<FileSystemResource> fullAudio(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        return media.fullAudio(access.requireReadable(id, user));
    }

    /** Dieselbe Tonspur als Download der kompletten Aufnahme. */
    @GetMapping("/{id}/audio/download")
    public ResponseEntity<FileSystemResource> fullAudioDownload(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        return media.fullAudioDownload(access.requireReadable(id, user));
    }

    @GetMapping("/{id}/video")
    public ResponseEntity<FileSystemResource> video(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        return media.video(access.requireReadable(id, user));
    }

    @GetMapping("/{id}/video/download")
    public ResponseEntity<FileSystemResource> videoDownload(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        return media.videoDownload(access.requireReadable(id, user));
    }

    /**
     * Zusammengefuehrtes Gesamt-Transkript: alle Segment-Transkripte mit
     * fortlaufenden Zeitstempeln, sowohl als Text als auch strukturiert
     * (Startzeit/Sprecher/Text) fuer die Anzeige im Frontend.
     */
    @GetMapping("/{id}/transcript")
    public Dtos.TranscriptView transcript(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireReadable(id, user);
        List<RecordingSegment> segments = segmentRepo.findByRecordingIdOrderBySeq(id);

        List<TranscriptAssembler.Entry> original = TranscriptAssembler.assemble(segments, false);
        boolean hasCorrected = segments.stream()
                .anyMatch(s -> s.getCorrectedText() != null && !s.getCorrectedText().isBlank());
        // Ohne Glaettung nicht dasselbe Transkript doppelt uebertragen
        List<TranscriptAssembler.Entry> corrected = hasCorrected
                ? TranscriptAssembler.assemble(segments, true) : List.of();

        return new Dtos.TranscriptView(
                TranscriptAssembler.toText(original), toEntryViews(original),
                hasCorrected ? TranscriptAssembler.toText(corrected) : null, toEntryViews(corrected),
                hasCorrected,
                recording.getCorrectionStatus() == null ? null : recording.getCorrectionStatus().name());
    }

    /**
     * Transkript als Datei. Die Fassung waehlt {@code variant}
     * ({@code corrected} = geglaettet, {@code original} = Whisper-Rohfassung),
     * das Format {@code format} ({@code md} oder {@code doc} fuer Word).
     */
    @GetMapping("/{id}/transcript/download")
    public ResponseEntity<byte[]> transcriptDownload(
            @PathVariable UUID id,
            @RequestParam(value = "variant", defaultValue = "corrected") String variant,
            @RequestParam(value = "format", defaultValue = "md") String format) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireReadable(id, user);
        return media.transcriptDownload(recording, requireOriginalVariant(variant),
                requireFormat(format));
    }

    /** {@code corrected} (Standard) oder {@code original}; alles andere ist ein Tippfehler. */
    private static boolean requireOriginalVariant(String variant) {
        String value = variant == null || variant.isBlank() ? "corrected" : variant.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "corrected" -> false;
            case "original" -> true;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unbekannte Fassung: " + variant + " (erlaubt: corrected, original)");
        };
    }

    private static bbbbot.export.ExportFormat requireFormat(String format) {
        try {
            return bbbbot.export.ExportFormat.parse(format);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static List<Dtos.TranscriptEntry> toEntryViews(List<TranscriptAssembler.Entry> entries) {
        return entries.stream()
                .map(e -> new Dtos.TranscriptEntry(e.startSeconds(), e.speaker(), e.text()))
                .toList();
    }

    // ------------------------------------------------------------ Teilnehmer

    /**
     * Teilnehmer umbenennen (nur Besitzer). Der neue Name ersetzt das
     * Diarisierungs-Label in der Transkript-Anzeige, in transcript.md und in
     * allen kuenftigen Zusammenfassungen; die Segment-Rohdaten bleiben unveraendert.
     */
    @PutMapping("/{id}/participants/{participantId}")
    public Dtos.ParticipantView renameParticipant(@PathVariable UUID id, @PathVariable UUID participantId,
                                                  @RequestBody Dtos.ParticipantUpdateRequest request) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireOwner(id, user);
        String name = request.displayName() == null ? "" : request.displayName().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name darf nicht leer sein");
        }
        if (name.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name ist zu lang (max. 200 Zeichen)");
        }
        Participant participant = participantRepo.findById(participantId)
                .filter(p -> p.getRecordingId().equals(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teilnehmer nicht gefunden"));
        participant.setDisplayName(name);
        participantRepo.save(participant);
        processingService.rewriteTranscriptFile(recording);
        return Dtos.ParticipantView.of(participant);
    }

    @PostMapping("/{id}/process")
    public Dtos.JobView processNow(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        access.requireReadable(id, user);
        try {
            ProcessingJob job = processingService.enqueueImmediate(id);
            // Sofort anstossen statt bis zu 30s auf den Scheduler zu warten,
            // damit der Nutzer im Frontend gleich Fortschritt sieht.
            processingService.poll();
            return Dtos.JobView.of(job);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Schritt 1 der manuellen Zwei-Schritt-Auswertung: nur die Transkription
     * laeuft sofort, die KI-Zusammenfassung wird nicht erstellt. Schritt 2
     * (Zusammenfassung) wird danach ueber POST /{id}/process angestossen.
     */
    @PostMapping("/{id}/transcribe")
    public Dtos.JobView transcribeOnly(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        // Nur der Besitzer: Anders als "Jetzt auswerten" (beschleunigt nur)
        // veraendert die Nur-Transkription das Ergebnis eines evtl. wartenden
        // automatischen Jobs - das darf kein Freigabe-Empfaenger ausloesen.
        access.requireOwner(id, user);
        try {
            ProcessingJob job = processingService.enqueueTranscribeOnly(id);
            // Sofort anstossen statt auf den Scheduler zu warten
            processingService.poll();
            return Dtos.JobView.of(job);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Erneute Auswertung einer bereits ausgewerteten Aufnahme (nur Besitzer):
     * Vorhandene Transkripte werden wiederverwendet, nur die Zusammenfassung
     * wird neu erstellt und ersetzt nach erfolgreichem Lauf die vorhandene.
     */
    @PostMapping("/{id}/reprocess")
    public Dtos.JobView reprocess(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        try {
            ProcessingJob job = processingService.enqueueReprocess(id);
            // Sofort anstossen statt auf den Scheduler zu warten
            processingService.poll();
            return Dtos.JobView.of(job);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Pro-Aufnahme-Einstellungen fuer Spracherkennung und Zusammenfassung setzen
     * (nur Besitzer). Leere Werte bedeuten "Admin-Standard verwenden". Die
     * Einstellungen wirken bei der naechsten Auswertung ("Jetzt/Erneut
     * auswerten", Transkription) - die Sprache der Spracherkennung greift dabei
     * nur, solange noch transkribiert wird (also vor der ersten Transkription
     * oder bei "Transkription neu erstellen").
     */
    @PostMapping("/{id}/summary-options")
    public Dtos.SummaryOptionsView updateSummaryOptions(@PathVariable UUID id,
                                                        @RequestBody Dtos.SummaryOptionsRequest request) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireOwner(id, user);

        String prompt = requireSummaryPrompt(request.prompt());
        Integer maxWords = request.maxWords();
        if (maxWords != null && (maxWords < 10 || maxWords > 10000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Maximale Laenge muss zwischen 10 und 10000 Woertern liegen");
        }
        String language = request.language() == null || request.language().isBlank()
                ? null : request.language().trim();
        if (language != null && language.length() > 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sprache ist zu lang");
        }

        recording.setSummaryPrompt(prompt);
        recording.setSummaryMaxWords(maxWords);
        recording.setSummaryLanguage(language);
        recording.setSttLanguage(requireSttLanguage(request.sttLanguage()));
        recordingRepo.save(recording);
        return summaryOptionsView(recording);
    }

    private Dtos.SummaryOptionsView summaryOptionsView(Recording recording) {
        return new Dtos.SummaryOptionsView(
                recording.getSummaryPrompt(), recording.getSummaryMaxWords(), recording.getSummaryLanguage(),
                recording.getSttLanguage(),
                settings.get(bbbbot.settings.SettingsService.SUMMARY_SYSTEM_PROMPT),
                settings.get(bbbbot.settings.SettingsService.SUMMARY_LANGUAGE),
                settings.get(bbbbot.settings.SettingsService.WHISPER_LANGUAGE));
    }

    /**
     * Erneute Transkription (nur Besitzer): Die Spracherkennung laeuft fuer
     * alle Segmente neu, anschliessend wird die Zusammenfassung neu erstellt.
     */
    @PostMapping("/{id}/retranscribe")
    public Dtos.JobView retranscribe(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        try {
            ProcessingJob job = processingService.enqueueRetranscribe(id);
            // Sofort anstossen statt auf den Scheduler zu warten
            processingService.poll();
            return Dtos.JobView.of(job);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // --------------------------------------------------------------- Sharing

    @GetMapping("/{id}/shares")
    public List<Dtos.ShareView> shares(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        return shareRepo.findByRecordingId(id).stream().map(this::toShareView).toList();
    }

    @PostMapping("/{id}/shares")
    public Dtos.ShareView share(@PathVariable UUID id, @RequestBody Dtos.ShareRequest request) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        if ((request.userId() == null) == (request.groupId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Entweder userId oder groupId angeben");
        }
        ShareGrant grant;
        if (request.userId() != null) {
            AppUser target = userRepo.findById(request.userId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nutzer nicht gefunden"));
            if (shareRepo.existsByRecordingIdAndGranteeUserId(id, target.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Bereits mit diesem Nutzer geteilt");
            }
            grant = ShareGrant.forUser(id, target.getId(), user.getId());
        } else {
            UserGroup group = groupRepo.findById(request.groupId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden"));
            if (shareRepo.existsByRecordingIdAndGranteeGroupId(id, group.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Bereits mit dieser Gruppe geteilt");
            }
            grant = ShareGrant.forGroup(id, group.getId(), user.getId());
        }
        shareRepo.save(grant);
        return toShareView(grant);
    }

    @DeleteMapping("/{id}/shares/{shareId}")
    public void unshare(@PathVariable UUID id, @PathVariable UUID shareId) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        shareRepo.findById(shareId)
                .filter(s -> s.getRecordingId().equals(id))
                .ifPresent(shareRepo::delete);
    }

    // ---------------------------------------------------- Freigabe-Links (oeffentlich)

    @GetMapping("/{id}/share-links")
    public List<Dtos.ShareLinkView> shareLinks(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        return shareLinkService.linksOf(id).stream()
                .map(l -> Dtos.ShareLinkView.of(l, shareLinkService.requiresLogin(l)))
                .toList();
    }

    /**
     * Erzeugt einen Freigabe-Link (nur Besitzer). Standard ist ein
     * kontogebundener Link: Der Empfaenger meldet sich an und bekommt die
     * Aufnahme dabei automatisch freigegeben. Mit {@code requireLogin=false}
     * entsteht ein offener Link - wer die Adresse kennt, sieht Video, Audio,
     * Transkript und Zusammenfassung ohne Anmeldung; das kann der Admin
     * installationsweit verbieten. Ohne {@code expiresInDays} gilt der Link bis
     * zum Widerruf.
     */
    @PostMapping("/{id}/share-links")
    public Dtos.ShareLinkView createShareLink(@PathVariable UUID id,
                                             @RequestBody(required = false) Dtos.ShareLinkRequest request) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        Integer days = request == null ? null : request.expiresInDays();
        if (days != null && (days < 1 || days > ShareLinkService.MAX_EXPIRY_DAYS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Laufzeit muss zwischen 1 und " + ShareLinkService.MAX_EXPIRY_DAYS + " Tagen liegen");
        }
        // Fehlende Angabe = kontogebunden: Der sicherere Fall ist der Standard.
        boolean requireLogin = request == null || request.requireLogin() == null
                || request.requireLogin();
        if (!requireLogin && !shareLinkService.publicLinksAllowed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Freigabe-Links ohne Anmeldung sind auf diesem Server abgeschaltet");
        }
        if (shareLinkService.countOf(id) >= ShareLinkService.MAX_LINKS_PER_RECORDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximal " + ShareLinkService.MAX_LINKS_PER_RECORDING
                            + " Freigabe-Links pro Aufnahme - bitte nicht mehr benoetigte widerrufen");
        }
        ShareLink link = shareLinkService.create(id, user.getId(), days, requireLogin);
        return Dtos.ShareLinkView.of(link, shareLinkService.requiresLogin(link));
    }

    /** Widerruft einen Freigabe-Link; die Adresse ist danach sofort ungueltig. */
    @DeleteMapping("/{id}/share-links/{linkId}")
    public void deleteShareLink(@PathVariable UUID id, @PathVariable UUID linkId) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        shareLinkService.findOfRecording(id, linkId).ifPresent(shareLinkService::delete);
    }

    // ------------------------------------------------------- Zusammenfassung

    @GetMapping("/{id}/summary")
    public Dtos.SummaryView latestSummary(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        access.requireReadable(id, user);
        return summaryRepo.findByRecordingIdOrderByCreatedAtDesc(id).stream()
                .findFirst()
                .map(Dtos.SummaryView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Keine Zusammenfassung vorhanden"));
    }

    /** @param format {@code md} (Standard) oder {@code doc} fuer die Word-Fassung */
    @GetMapping("/{id}/summary/download")
    public ResponseEntity<byte[]> downloadSummary(
            @PathVariable UUID id,
            @RequestParam(value = "format", defaultValue = "md") String format) {
        AppUser user = CurrentUser.get();
        return media.summaryDownload(access.requireReadable(id, user), requireFormat(format));
    }

    /**
     * Zusammenfassung nachtraeglich haendisch bearbeiten (nur Besitzer). Der
     * neue Markdown-Inhalt ersetzt den bisherigen; summary.md wird aktualisiert,
     * sofern es sich um die neueste fertige Zusammenfassung handelt. Eine
     * spaetere "Erneut auswerten"-Auswertung ueberschreibt die Bearbeitung.
     */
    @PutMapping("/{id}/summaries/{summaryId}")
    public Dtos.SummaryView updateSummary(@PathVariable UUID id, @PathVariable UUID summaryId,
                                          @RequestBody Dtos.SummaryUpdateRequest request) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireOwner(id, user);
        String markdown = request.markdown();
        if (markdown == null || markdown.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Zusammenfassung darf nicht leer sein - zum Entfernen bitte loeschen");
        }
        Summary summary = summaryRepo.findById(summaryId)
                .filter(s -> s.getRecordingId().equals(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zusammenfassung nicht gefunden"));
        if (summary.getStatus() != Summary.Status.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Nur fertige Zusammenfassungen koennen bearbeitet werden");
        }
        summary.setMarkdown(markdown.trim() + "\n");
        summaryRepo.save(summary);
        summaryService.syncSummaryFile(recording);
        return Dtos.SummaryView.of(summary);
    }

    @DeleteMapping("/{id}/summaries/{summaryId}")
    public void deleteSummary(@PathVariable UUID id, @PathVariable UUID summaryId) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireOwner(id, user);
        summaryRepo.findById(summaryId)
                .filter(s -> s.getRecordingId().equals(id))
                .ifPresent(summaryRepo::delete);
        // summary.md nachziehen, damit die Datei keinen geloeschten Inhalt behaelt
        summaryService.syncSummaryFile(recording);
    }

    // ---------------------------------------------------------------- intern

    /** Laeuft zu dieser Aufnahme gerade ein Bot oder eine Bildschirmaufnahme im Browser? */
    private boolean isCapturing(UUID recordingId) {
        return botManager.isRecordingActive(recordingId) || captureService.isActive(recordingId);
    }

    /** Einzelansicht: Schlagworte werden fuer diese eine Aufnahme nachgeladen. */
    private Dtos.RecordingView toView(Recording recording, AppUser user) {
        return toView(recording, user, tagService.tagsOf(recording.getId()));
    }

    private Dtos.RecordingView toView(Recording recording, AppUser user, List<String> tags) {
        AppUser owner = userRepo.findById(recording.getOwnerId()).orElse(null);
        return Dtos.RecordingView.of(recording, recording.getOwnerId().equals(user.getId()), owner, tags);
    }

    private Dtos.ShareView toShareView(ShareGrant grant) {
        Dtos.UserView userView = grant.getGranteeUserId() == null ? null
                : userRepo.findById(grant.getGranteeUserId()).map(Dtos.UserView::of).orElse(null);
        Dtos.GroupView groupView = grant.getGranteeGroupId() == null ? null
                : groupRepo.findById(grant.getGranteeGroupId())
                        .map(g -> Dtos.GroupView.of(g, grant.getCreatedBy())).orElse(null);
        return new Dtos.ShareView(grant.getId(), grant.getRecordingId(), userView, groupView, grant.getCreatedAt());
    }

    private void deleteDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Dateien konnten nicht geloescht werden: " + e.getMessage());
        }
    }
}
