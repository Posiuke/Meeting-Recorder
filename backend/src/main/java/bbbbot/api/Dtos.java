package bbbbot.api;

import bbbbot.domain.AppUser;
import bbbbot.domain.BotSession;
import bbbbot.domain.BotTemplate;
import bbbbot.domain.Participant;
import bbbbot.domain.ProcessingJob;
import bbbbot.domain.PromptTemplate;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.ShareLink;
import bbbbot.domain.Summary;
import bbbbot.domain.UserGroup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API-Datentransfer-Objekte. */
public final class Dtos {

    private Dtos() {}

    public record UserView(UUID id, String username, String displayName, String email, boolean admin,
                           boolean local, boolean mustChangePassword, String language) {
        public static UserView of(AppUser u) {
            boolean local = u.getPasswordHash() != null && !u.getPasswordHash().isBlank();
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.isAdmin(),
                    local, u.isMustChangePassword(), u.getLanguage());
        }
    }

    /**
     * Laufende Aufnahme eines Nutzers in der Admin-Uebersicht - genug, um sie
     * zuzuordnen, ohne Inhalte offenzulegen (kein Transkript, keine URL).
     */
    public record ActiveRecordingView(UUID id, String title, String status, String source,
                                      Instant startedAt) {
        public static ActiveRecordingView of(Recording r) {
            Recording.Source src = r.getSource() == null ? Recording.Source.BOT : r.getSource();
            return new ActiveRecordingView(r.getId(), r.getTitle(), r.getStatus().name(),
                    src.name(), r.getStartedAt());
        }
    }

    /**
     * Nutzer in der Admin-Verwaltung: zusaetzlich zu {@link UserView} der
     * Aktivitaetszustand und die gerade laufenden Aufnahmen. Damit sieht ein
     * Admin vor Wartungsarbeiten, wen ein Neustart mitten in einer Aufnahme
     * treffen wuerde.
     */
    public record AdminUserView(UUID id, String username, String displayName, String email,
                                boolean admin, boolean local, boolean mustChangePassword,
                                String language, Instant lastLoginAt, Instant lastSeenAt,
                                boolean online, List<ActiveRecordingView> activeRecordings) {
        public static AdminUserView of(AppUser u, boolean online, List<ActiveRecordingView> active) {
            boolean local = u.getPasswordHash() != null && !u.getPasswordHash().isBlank();
            return new AdminUserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                    u.isAdmin(), local, u.isMustChangePassword(), u.getLanguage(),
                    u.getLastLoginAt(), u.getLastSeenAt(), online,
                    active == null ? List.of() : active);
        }
    }

    /** Oberflaechensprache des angemeldeten Nutzers setzen. */
    public record LanguageRequest(String language) {}

    public record LoginRequest(String username, String password) {}

    public record LoginResponse(String token, UserView user) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record LdapTestRequest(String username, String password) {}

    public record LdapTestResult(boolean success, String message, String displayName, String email) {}

    /** Ergebnis eines Verbindungstests (Whisper/LLM) aus dem Admin-Bereich. */
    public record ConnectionTestResult(boolean success, String message, long durationMs) {}

    /**
     * @param sttLanguage Sprache der Spracherkennung fuer die Aufnahmen dieses
     *                    Bots; leer = Admin-Standard, "auto" = automatisch erkennen
     */
    public record StartBotRequest(String meetingUrl, String botName, Boolean autoRecord,
                                  Boolean recordVideo, Boolean aiAnalysis, Boolean diarize,
                                  String sttLanguage) {}

    /**
     * Persoenliche Bot-Vorlage: ein benannter Meetingraum samt Einstellungen,
     * aus dem sich ein Bot ohne weitere Eingabe starten laesst.
     *
     * @param diarize      Wunsch des Nutzers; ob die Sprechererkennung wirklich
     *                     laeuft, entscheidet beim Start {@code whisper.diarize}
     * @param sttLanguage  null = Admin-Standard, "auto" = selbst erkennen
     */
    public record BotTemplateView(UUID id, String name, String meetingUrl, String botName,
                                  boolean autoRecord, boolean recordVideo, boolean aiAnalysis,
                                  boolean diarize, String sttLanguage,
                                  Instant createdAt, Instant updatedAt) {
        public static BotTemplateView of(BotTemplate t) {
            return new BotTemplateView(t.getId(), t.getName(), t.getMeetingUrl(), t.getBotName(),
                    t.isAutoRecord(), t.isRecordVideo(), t.isAiAnalysis(), t.isDiarize(),
                    t.getSttLanguage(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    /** Wie {@link StartBotRequest}, nur mit Namen der Vorlage statt Sofort-Start. */
    public record BotTemplateRequest(String name, String meetingUrl, String botName,
                                     Boolean autoRecord, Boolean recordVideo, Boolean aiAnalysis,
                                     Boolean diarize, String sttLanguage) {}

    public record BotView(UUID sessionId, String status, String meetingUrl, String roomName,
                          String botName, boolean autoRecord, boolean recordVideo, boolean aiAnalysis,
                          UUID recordingId, int participants, int audioTracks,
                          String lastError, Instant createdAt, boolean mine) {}

    /**
     * @param hasAudio wirklich abspielbar: Der Pfad steht nicht nur in der
     *                 Datenbank, die Datei liegt auch da. Bei alten Aufnahmen
     *                 koennen beide auseinanderlaufen (verschobener oder
     *                 aufgeraeumter Speicher) - das Frontend bietet die
     *                 Wiedergabe sonst an und laeuft in einen Fehler.
     */
    public record SegmentView(UUID id, int seq, String status, Long durationMs, Long sizeBytes,
                              boolean hasAudio, boolean hasTranscript) {
        public static SegmentView of(RecordingSegment s) {
            return new SegmentView(s.getId(), s.getSeq(), s.getStatus().name(), s.getDurationMs(),
                    s.getSizeBytes(), audioExists(s),
                    s.getTranscriptText() != null && !s.getTranscriptText().isBlank());
        }

        private static boolean audioExists(RecordingSegment s) {
            return s.getMp3Path() != null && !s.getMp3Path().isBlank()
                    && java.nio.file.Files.exists(java.nio.file.Path.of(s.getMp3Path()));
        }
    }

    /**
     * Eine Fassung der Zusammenfassung.
     *
     * @param templateName Vorlage, mit der die Fassung erzeugt wurde (null = keine benannte)
     * @param systemPrompt Auswertungs-Prompt dieser Fassung - damit sich zwei
     *                     Fassungen nicht nur am Ergebnis vergleichen lassen
     * @param current      die eine Fassung, die als "die" Zusammenfassung gilt
     * @param editedAt     Zeitpunkt der letzten haendischen Bearbeitung (null = unberuehrt)
     */
    public record SummaryView(UUID id, String status, String markdown, String model,
                              Double temperature, String templateName, String systemPrompt,
                              boolean current, String error, Instant createdAt,
                              Instant finishedAt, Instant editedAt) {
        public static SummaryView of(Summary s) {
            return new SummaryView(s.getId(), s.getStatus().name(), s.getMarkdown(), s.getModel(),
                    s.getTemperature(), s.getTemplateName(), s.getSystemPrompt(), s.isCurrent(),
                    s.getError(), s.getCreatedAt(), s.getFinishedAt(), s.getEditedAt());
        }
    }

    /** Haendisch bearbeiteter Inhalt einer Zusammenfassung. */
    /**
     * Eine der Aufnahme beigefuegte Unterlage. Der extrahierte Text selbst ist
     * NICHT dabei - er kann hunderttausend Zeichen haben und wird bei Bedarf
     * einzeln abgerufen ({@code /documents/{documentId}/text}).
     *
     * @param status    PENDING (Text wird noch geholt), READY, FAILED
     * @param textChars Zeichen des extrahierten Textes; macht sichtbar, ob wirklich
     *                  Text herauskam - ein Scan ohne OCR liefert nichts
     * @param error     Grund, wenn kein Text herauskam (z.B. fehlender Tika-Server)
     */
    public record RecordingDocumentView(UUID id, String filename, String contentType,
                                        long sizeBytes, String status, Integer textChars,
                                        String error, Instant createdAt, Instant extractedAt) {
        public static RecordingDocumentView of(bbbbot.domain.RecordingDocument d) {
            return new RecordingDocumentView(d.getId(), d.getFilename(), d.getContentType(),
                    d.getSizeBytes(), d.getStatus().name(), d.getTextChars(), d.getError(),
                    d.getCreatedAt(), d.getExtractedAt());
        }
    }

    public record SummaryUpdateRequest(String markdown) {}

    /**
     * Ein Verarbeitungsauftrag an einer Aufnahme.
     *
     * @param startedAt   Beginn des laufenden bzw. letzten Versuchs - die
     *                    Oberflaeche zeigt daraus "laeuft seit 4 Minuten"
     * @param maxAttempts nach so vielen Versuchen gibt die Verarbeitung auf;
     *                    gehoert zur Anzeige "Versuch 3 von 3"
     */
    public record JobView(UUID id, String status, boolean immediate, boolean transcribeOnly,
                          int attempts, int maxAttempts, String lastError, Instant createdAt,
                          Instant startedAt, Instant finishedAt) {
        public static JobView of(ProcessingJob j) {
            return new JobView(j.getId(), j.getStatus().name(), j.isImmediate(), j.isTranscribeOnly(),
                    j.getAttempts(), bbbbot.processing.ProcessingService.MAX_JOB_ATTEMPTS,
                    j.getLastError(), j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt());
        }
    }

    /**
     * Rahmenbedingungen der Verarbeitung fuer JEDEN angemeldeten Nutzer - nicht
     * nur fuer Admins.
     *
     * <p>Grund: Die Aufnahme-Detailseite sagte bisher "ist fuer das naechtliche
     * Zeitfenster eingeplant", ohne die Uhrzeit zu nennen; die stand nur in den
     * Admin-Einstellungen. Der Unterschied zwischen "irgendwann nachts" und
     * "ab 20:00" entscheidet, ob jemand in 20 Minuten oder morgen wieder
     * nachsieht. Bewusst nur diese drei Angaben - Warteschlangenlaengen und
     * Fehlerquoten bleiben dem Admin-Bereich.
     */
    public record ProcessingInfoView(String windowStart, String windowEnd, boolean windowOpen) {}

    /**
     * Ein Verarbeitungsauftrag in der Admin-Uebersicht: der Auftrag selbst plus
     * die Angaben der Aufnahme, damit die Tabelle ohne zweiten Aufruf lesbar ist.
     *
     * @param mode            Kurzform der Aufgabe (FULL, TRANSCRIBE_ONLY, REPROCESS,
     *                        RETRANSCRIBE, RETRANSCRIBE_ONLY)
     * @param maxAttempts     nach so vielen Versuchen gibt die Verarbeitung auf
     * @param waitingMs       wie lange der Auftrag auf seinen Start gewartet hat
     * @param waitsForWindow  steht nur, weil das Zeitfenster zu ist
     * @param sttMs           Dauer der Spracherkennung; null = lief in diesem Auftrag nicht
     * @param correctionMs    Dauer der Transkript-Glaettung; null = lief nicht
     * @param summaryMs       Dauer der Zusammenfassung; null = lief nicht
     */
    public record ProcessingJobView(UUID id, UUID recordingId, String recordingTitle,
                                    String recordingStatus, String status, String mode,
                                    boolean immediate, int attempts, int maxAttempts,
                                    String lastError, Instant createdAt, Instant startedAt,
                                    Instant finishedAt, Long waitingMs, Long durationMs,
                                    Long sttMs, Long correctionMs, Long summaryMs,
                                    boolean waitsForWindow) {

        public static ProcessingJobView of(bbbbot.processing.ProcessingQueueService.JobDetail detail,
                                           int maxAttempts) {
            ProcessingJob job = detail.job();
            Recording recording = detail.recording();
            return new ProcessingJobView(
                    job.getId(), job.getRecordingId(),
                    recording == null ? null : recording.getTitle(),
                    recording == null ? null : recording.getStatus().name(),
                    job.getStatus().name(), job.mode(), job.isImmediate(),
                    job.getAttempts(), maxAttempts, job.getLastError(),
                    job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(),
                    detail.waitingMs(), job.durationMs(),
                    job.getSttMs(), job.getCorrectionMs(), job.getSummaryMs(),
                    detail.waitsForWindow());
        }
    }

    /**
     * Dauer-Kennzahlen der letzten fertigen Auftraege.
     *
     * @param sample Anzahl der Auftraege, aus denen die Werte stammen
     */
    public record ProcessingDurationsView(int sample, Long medianMs, Long maxMs,
                                          Long medianSttMs, Long medianCorrectionMs,
                                          Long medianSummaryMs) {}

    /**
     * Der Admin-Tab "Verarbeitung": Zustand der Warteschlange, die letzten
     * Fehlschlaege und ob das Zeitfenster gerade offen ist.
     */
    public record ProcessingQueueView(boolean windowOpen, String windowStart, String windowEnd,
                                      long pending, long running, long failed, long done,
                                      List<ProcessingJobView> queue,
                                      List<ProcessingJobView> failures,
                                      ProcessingDurationsView durations) {

        public static ProcessingQueueView of(bbbbot.processing.ProcessingQueueService.Overview o,
                                             int maxAttempts) {
            return new ProcessingQueueView(o.windowOpen(), o.windowStart(), o.windowEnd(),
                    o.pending(), o.running(), o.failed(), o.done(),
                    o.queue().stream().map(d -> ProcessingJobView.of(d, maxAttempts)).toList(),
                    o.failures().stream().map(d -> ProcessingJobView.of(d, maxAttempts)).toList(),
                    new ProcessingDurationsView(o.durations().sample(), o.durations().medianMs(),
                            o.durations().maxMs(), o.durations().medianSttMs(),
                            o.durations().medianCorrectionMs(), o.durations().medianSummaryMs()));
        }
    }

    public record RecordingView(UUID id, String title, String status, String meetingUrl,
                                Instant startedAt, Instant endedAt, Long durationMs,
                                String discardReason, boolean recordVideo, boolean aiAnalysis,
                                String videoStatus, String source, List<String> tags,
                                boolean mine, UserView owner) {
        public static RecordingView of(Recording r, boolean mine, AppUser owner) {
            return of(r, mine, owner, List.of());
        }

        public static RecordingView of(Recording r, boolean mine, AppUser owner, List<String> tags) {
            Recording.Source src = r.getSource() == null ? Recording.Source.BOT : r.getSource();
            return new RecordingView(r.getId(), r.getTitle(), r.getStatus().name(), r.getMeetingUrl(),
                    r.getStartedAt(), r.getEndedAt(), r.getDurationMs(), r.getDiscardReason(),
                    r.isRecordVideo(), r.isAiAnalysis(),
                    r.getVideoStatus() == null ? null : r.getVideoStatus().name(),
                    src.name(), tags == null ? List.of() : tags,
                    mine, owner == null ? null : UserView.of(owner));
        }
    }

    /**
     * Eine Seite der Aufnahmenliste. Die Gesamtzahl gehoert dazu, sonst weiss die
     * Oberflaeche nicht, ob es hinter der Seite noch etwas gibt - und kann weder
     * "mehr laden" anbieten noch "25 von 340" anzeigen.
     *
     * @param page       Seitennummer, bei 0 beginnend
     * @param size       angeforderte Seitengroesse
     * @param total      Treffer insgesamt (ueber alle Seiten)
     * @param totalPages Anzahl der Seiten
     * @param hasMore    Gibt es hinter dieser Seite weitere Treffer?
     */
    public record RecordingPageView(List<RecordingView> items, int page, int size,
                                    long total, int totalPages, boolean hasMore) {}

    public record RecordingDetail(RecordingView recording, List<SegmentView> segments,
                                  List<SummaryView> summaries, List<JobView> jobs,
                                  List<ParticipantView> participants,
                                  List<RecordingDocumentView> documents,
                                  String participantsLog, String chatLog,
                                  SummaryOptionsView summaryOptions) {}

    /**
     * Rahmenbedingungen fuer beigefuegte Unterlagen, damit die Oberflaeche nicht
     * raten muss.
     *
     * @param tikaConfigured Ist ein Tika-Server eingerichtet? Ohne ihn lassen sich
     *                       nur Text- und Markdown-Dateien auswerten - das gehoert
     *                       vor den Upload gesagt, nicht danach.
     */
    public record DocumentConfigView(boolean enabled, long maxFileSizeBytes,
                                     List<String> extensions, boolean tikaConfigured) {}

    /**
     * Teilnehmer einer Aufnahme: aus der Diarisierung erkannter Sprecher
     * (speakerLabel) mit editierbarem Anzeigenamen.
     */
    public record ParticipantView(UUID id, String speakerLabel, String displayName) {
        public static ParticipantView of(Participant p) {
            return new ParticipantView(p.getId(), p.getSpeakerLabel(), p.getDisplayName());
        }
    }

    public record ParticipantUpdateRequest(String displayName) {}

    /** Schlagwort mit Anzahl der Aufnahmen (Filterleiste, Vorschlagsliste). */
    public record TagCountView(String name, long count) {}

    public record TagRequest(String name) {}

    /**
     * Pro-Aufnahme-Einstellungen fuer Spracherkennung und Zusammenfassung
     * (null = Admin-Standard). Die Defaults werden mitgeliefert, damit das
     * Frontend anzeigen kann, was "Standard" konkret bedeutet.
     *
     * @param language           Sprache der Zusammenfassung
     * @param sttLanguage        Sprache der Spracherkennung ("auto" = automatisch erkennen)
     * @param model              Modell dieser Aufnahme (null = Admin-Vorgabe)
     * @param temperature        Temperatur dieser Aufnahme (null = Admin-Vorgabe)
     * @param defaultSttLanguage Admin-Standard der Spracherkennung (whisper.language);
     *                           leer bedeutet dort ebenfalls "automatisch erkennen"
     * @param defaultModel       Admin-Vorgabe {@code llm.model}
     * @param defaultTemperature Admin-Vorgabe {@code llm.temperature}
     */
    public record SummaryOptionsView(String prompt, String templateName, Integer maxWords,
                                     String language, String sttLanguage, String model,
                                     Double temperature, String defaultPrompt,
                                     String defaultLanguage, String defaultSttLanguage,
                                     String defaultModel, double defaultTemperature) {}

    public record SummaryOptionsRequest(String prompt, String templateName, Integer maxWords,
                                        String language, String sttLanguage, String model,
                                        Double temperature) {}

    /**
     * Persoenliche Promptvorlage des angemeldeten Nutzers.
     *
     * @param model       Modell dieser Vorlage (null = Admin-Vorgabe {@code llm.model})
     * @param temperature Temperatur dieser Vorlage (null = Admin-Vorgabe)
     */
    public record PromptTemplateView(UUID id, String name, String prompt, String model,
                                     Double temperature, Instant createdAt, Instant updatedAt) {
        public static PromptTemplateView of(PromptTemplate t) {
            return new PromptTemplateView(t.getId(), t.getName(), t.getPrompt(), t.getModel(),
                    t.getTemperature(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record PromptTemplateRequest(String name, String prompt, String model,
                                        Double temperature) {}

    /**
     * Standardvorgabe des Administrators fuer die Auswertung. Sie dient auf der
     * Vorlagen-Seite als Ausgangspunkt fuer eigene Vorlagen (dieselbe Angabe
     * steckt bereits in {@link SummaryOptionsView#defaultPrompt()}).
     */
    public record DefaultPromptView(String prompt) {}

    /** Eintrag im persoenlichen Glossar (Abkuerzung/Fachbegriff mit Bedeutung). */
    public record GlossaryEntryView(UUID id, String term, String meaning,
                                    Instant createdAt, Instant updatedAt) {
        public static GlossaryEntryView of(bbbbot.domain.GlossaryEntry e) {
            return new GlossaryEntryView(e.getId(), e.getTerm(), e.getMeaning(),
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record GlossaryEntryRequest(String term, String meaning) {}

    /**
     * API-Schluessel in der Uebersicht. Das Token selbst kommt hier bewusst NICHT
     * vor - gespeichert ist nur sein Abdruck, angezeigt wird der Anfang
     * ({@code prefix}) zum Wiedererkennen.
     */
    public record ApiKeyView(UUID id, String name, String prefix, boolean readOnly,
                             Instant createdAt, Instant expiresAt, Instant lastUsedAt,
                             boolean expired) {
        public static ApiKeyView of(bbbbot.domain.ApiKey k) {
            return new ApiKeyView(k.getId(), k.getName(), k.getTokenPrefix(), k.isReadOnly(),
                    k.getCreatedAt(), k.getExpiresAt(), k.getLastUsedAt(),
                    k.isExpired(Instant.now()));
        }
    }

    /** Antwort beim Anlegen: einmalig mit dem Klartext-Token. */
    public record ApiKeyCreated(ApiKeyView key, String token) {}

    /** @param expiresAt ISO-Zeitpunkt oder leer fuer unbegrenzt gueltig */
    public record ApiKeyRequest(String name, Boolean readOnly, String expiresAt) {}

    /**
     * Direkte Transkription per API: Zustand eines Auftrags. {@code text} ist
     * erst bei {@code status=DONE} gefuellt, {@code error} nur bei FAILED.
     */
    public record TranscriptionView(UUID id, String status, String text,
                                    List<TranscriptEntry> entries, Long durationMs,
                                    String error) {}

    /** Ein Eintrag des zusammengefuehrten Transkripts (Startzeit ab Aufnahmebeginn). */
    public record TranscriptEntry(long startSeconds, String speaker, String text) {}

    /**
     * Transkript in beiden Fassungen: {@code transcript}/{@code entries} ist das
     * Whisper-Original, {@code corrected*} die KI-geglaettete Fassung. Beide werden
     * zusammen geliefert, damit der Umschalter im Frontend ohne Nachladen wirkt;
     * ohne Glaettung sind die corrected-Felder leer und {@code hasCorrected} false.
     */
    public record TranscriptView(String transcript, List<TranscriptEntry> entries,
                                 String correctedTranscript, List<TranscriptEntry> correctedEntries,
                                 boolean hasCorrected, String correctionStatus) {}

    public record ShareRequest(UUID userId, UUID groupId) {}

    public record ShareView(UUID id, UUID recordingId, UserView user, GroupView group, Instant createdAt) {}

    /**
     * Oeffentlicher Freigabe-Link. {@code token} ist das Zugriffsmerkmal; die
     * vollstaendige Adresse setzt das Frontend daraus zusammen, damit der Server
     * seine eigene oeffentliche Adresse nicht kennen muss.
     *
     * @param expiresInDays Laufzeit in Tagen oder {@code null} fuer "bis zum Widerruf"
     * @param requireLogin  true (Standard) = Empfaenger muss sich anmelden und
     *                      bekommt die Aufnahme dabei mit seinem Konto freigegeben;
     *                      false = Zugriff allein ueber die Adresse
     */
    public record ShareLinkRequest(Integer expiresInDays, Boolean requireLogin) {}

    /**
     * @param requiresLogin was fuer diesen Link tatsaechlich gilt - der Wunsch des
     *                      Besitzers ODER die Admin-Notbremse (Zugriff ohne
     *                      Anmeldung installationsweit abgeschaltet)
     */
    public record ShareLinkView(UUID id, String token, Instant createdAt, Instant expiresAt,
                                boolean expired, int views, Instant lastViewedAt,
                                boolean requiresLogin) {
        public static ShareLinkView of(ShareLink link, boolean requiresLogin) {
            return new ShareLinkView(link.getId(), link.getToken(), link.getCreatedAt(),
                    link.getExpiresAt(), link.isExpired(Instant.now()),
                    link.getViews(), link.getLastViewedAt(), requiresLogin);
        }
    }

    /**
     * Ergebnis des Einloesens eines Freigabe-Links.
     *
     * @param shared true = die Aufnahme wurde dabei neu mit dem Konto geteilt
     */
    public record ShareLinkClaimView(UUID recordingId, String title, boolean shared) {}

    /**
     * Alles, was die oeffentliche Freigabe-Ansicht zeigt: Kopfdaten, Video,
     * Audio-Segmente, Transkript und Zusammenfassung. Bewusst NICHT enthalten
     * sind Chat- und Sitzungsprotokoll sowie die Verarbeitungs-Historie - die
     * bleiben der angemeldeten Ansicht vorbehalten.
     *
     * @param transcript Zusammengefuehrtes Transkript (geglaettete Fassung, falls vorhanden)
     * @param summary    Aktuelle Fassung der Zusammenfassung als Markdown; null wenn keine existiert
     * @param language   Oberflaechensprache des Freigebenden (null = nie gewaehlt). Die
     *                   Freigabe-Ansicht startet damit statt mit der Browsersprache des
     *                   Empfaengers: Inhalt und Beschriftung passen so eher zusammen.
     */
    public record PublicShareView(String title, Instant startedAt, Instant endedAt, Long durationMs,
                                  String source, String sharedBy, boolean hasVideo,
                                  List<PublicSegmentView> segments,
                                  String summary, Instant summaryCreatedAt,
                                  String transcript, List<TranscriptEntry> entries,
                                  List<ParticipantView> participants, Instant expiresAt,
                                  String language) {}

    /** Abspielbares Audio-Segment in der Freigabe-Ansicht. */
    public record PublicSegmentView(UUID id, int seq, Long durationMs, Long sizeBytes) {
        public static PublicSegmentView of(RecordingSegment s) {
            return new PublicSegmentView(s.getId(), s.getSeq(), s.getDurationMs(), s.getSizeBytes());
        }
    }

    public record GroupView(UUID id, String name, UUID ownerId, boolean mine, Instant createdAt) {
        public static GroupView of(UserGroup g, UUID currentUserId) {
            return new GroupView(g.getId(), g.getName(), g.getOwnerId(),
                    g.getOwnerId().equals(currentUserId), g.getCreatedAt());
        }
    }

    public record GroupMemberView(UUID userId, String username, String displayName, Instant addedAt) {}

    public record CreateGroupRequest(String name) {}

    public record AddMemberRequest(UUID userId) {}

    public record SetAdminRequest(boolean admin) {}

    public record BotSessionHistoryView(UUID id, String meetingUrl, String roomName, String botName,
                                        String status, Instant createdAt, Instant endedAt, String lastError) {
        public static BotSessionHistoryView of(BotSession s) {
            return new BotSessionHistoryView(s.getId(), s.getMeetingUrl(), s.getRoomName(), s.getBotName(),
                    s.getStatus().name(), s.getCreatedAt(), s.getEndedAt(), s.getLastError());
        }
    }
}
