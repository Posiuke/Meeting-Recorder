package bbbbot.api;

import bbbbot.domain.AppUser;
import bbbbot.domain.BotSession;
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

    public record SummaryView(UUID id, String status, String markdown, String model,
                              String error, Instant createdAt, Instant finishedAt) {
        public static SummaryView of(Summary s) {
            return new SummaryView(s.getId(), s.getStatus().name(), s.getMarkdown(), s.getModel(),
                    s.getError(), s.getCreatedAt(), s.getFinishedAt());
        }
    }

    /** Haendisch bearbeiteter Inhalt einer Zusammenfassung. */
    public record SummaryUpdateRequest(String markdown) {}

    public record JobView(UUID id, String status, boolean immediate, boolean transcribeOnly,
                          int attempts, String lastError, Instant createdAt, Instant finishedAt) {
        public static JobView of(ProcessingJob j) {
            return new JobView(j.getId(), j.getStatus().name(), j.isImmediate(), j.isTranscribeOnly(),
                    j.getAttempts(), j.getLastError(), j.getCreatedAt(), j.getFinishedAt());
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

    public record RecordingDetail(RecordingView recording, List<SegmentView> segments,
                                  List<SummaryView> summaries, List<JobView> jobs,
                                  List<ParticipantView> participants,
                                  String participantsLog, String chatLog,
                                  SummaryOptionsView summaryOptions) {}

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
     * @param defaultSttLanguage Admin-Standard der Spracherkennung (whisper.language);
     *                           leer bedeutet dort ebenfalls "automatisch erkennen"
     */
    public record SummaryOptionsView(String prompt, Integer maxWords, String language,
                                     String sttLanguage, String defaultPrompt,
                                     String defaultLanguage, String defaultSttLanguage) {}

    public record SummaryOptionsRequest(String prompt, Integer maxWords, String language,
                                        String sttLanguage) {}

    /** Persoenliche Promptvorlage des angemeldeten Nutzers. */
    public record PromptTemplateView(UUID id, String name, String prompt,
                                     Instant createdAt, Instant updatedAt) {
        public static PromptTemplateView of(PromptTemplate t) {
            return new PromptTemplateView(t.getId(), t.getName(), t.getPrompt(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record PromptTemplateRequest(String name, String prompt) {}

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
     * @param summary    Neueste fertige Zusammenfassung als Markdown; null wenn keine existiert
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
