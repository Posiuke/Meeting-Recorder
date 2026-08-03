package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recording")
public class Recording {

    public enum Status { RECORDING, FINALIZING, RECORDED, PROCESSING, TRANSCRIBED, DONE, FAILED, DISCARDED }

    /** Zustand der optionalen Video-Aufzeichnung (Browser-Ansicht -> MP4). */
    public enum VideoStatus { NONE, RECORDING, MUXING, READY, FAILED }

    /**
     * Woher die Aufnahme stammt: BOT = Bot-Session im Meeting, UPLOAD = vom Nutzer
     * hochgeladene Datei, CAPTURE = im Browser des Nutzers aufgezeichneter Bildschirm.
     */
    public enum Source { BOT, UPLOAD, CAPTURE }

    /**
     * Zustand der KI-Glaettung des Transkripts: NONE = nicht versucht (oder
     * abgeschaltet), READY = geglaettete Fassung liegt vor, FAILED = Glaettung
     * misslungen (die Auswertung laeuft dann mit dem Original weiter).
     */
    public enum CorrectionStatus { NONE, READY, FAILED }

    @Id
    private UUID id;

    private UUID botSessionId;

    @Column(nullable = false)
    private UUID ownerId;

    private String title;

    private String meetingUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source = Source.BOT;

    /**
     * Zeitpunkt des zuletzt empfangenen Chunks einer Bildschirmaufnahme; nur bei
     * {@link Source#CAPTURE} gesetzt. Bleibt der Wert stehen, gilt die Aufnahme
     * als abgebrochen und wird vom Sweeper gerettet.
     */
    private Instant captureLastChunkAt;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    @Column(nullable = false)
    private String directory;

    @Column(columnDefinition = "text")
    private String participantsLog;

    @Column(columnDefinition = "text")
    private String chatLog;

    private Long durationMs;

    private String discardReason;

    @Column(nullable = false)
    private boolean recordVideo = false;

    @Column(nullable = false)
    private boolean aiAnalysis = true;

    /** Sprechererkennung gewuenscht (greift nur, wenn der Admin sie freigeschaltet hat). */
    @Column(nullable = false)
    private boolean diarize = false;

    private String videoPath;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private VideoStatus videoStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CorrectionStatus correctionStatus;

    /** Eigener Auswertungs-Prompt fuer diese Aufnahme (null = Admin-Standard). */
    @Column(columnDefinition = "text")
    private String summaryPrompt;

    /** Maximale Laenge der Zusammenfassung in Woertern (null = keine Vorgabe). */
    private Integer summaryMaxWords;

    /** Sprache der Zusammenfassung (null = Admin-Standard). */
    @Column(length = 16)
    private String summaryLanguage;

    public static Recording start(UUID botSessionId, UUID ownerId, String meetingUrl, String directory,
                                  boolean recordVideo, boolean aiAnalysis, boolean diarize) {
        Recording r = new Recording();
        r.id = UUID.randomUUID();
        r.botSessionId = botSessionId;
        r.ownerId = ownerId;
        r.meetingUrl = meetingUrl;
        r.status = Status.RECORDING;
        r.source = Source.BOT;
        r.startedAt = Instant.now();
        r.directory = directory;
        r.recordVideo = recordVideo;
        r.aiAnalysis = aiAnalysis;
        r.diarize = diarize;
        r.videoStatus = recordVideo ? VideoStatus.RECORDING : VideoStatus.NONE;
        return r;
    }

    public UUID getId() { return id; }
    public UUID getBotSessionId() { return botSessionId; }
    public UUID getOwnerId() { return ownerId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMeetingUrl() { return meetingUrl; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Instant getCaptureLastChunkAt() { return captureLastChunkAt; }
    public void setCaptureLastChunkAt(Instant captureLastChunkAt) { this.captureLastChunkAt = captureLastChunkAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }
    public String getParticipantsLog() { return participantsLog; }
    public void setParticipantsLog(String participantsLog) { this.participantsLog = participantsLog; }
    public String getChatLog() { return chatLog; }
    public void setChatLog(String chatLog) { this.chatLog = chatLog; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getDiscardReason() { return discardReason; }
    public void setDiscardReason(String discardReason) { this.discardReason = discardReason; }
    public boolean isRecordVideo() { return recordVideo; }
    public void setRecordVideo(boolean recordVideo) { this.recordVideo = recordVideo; }
    public boolean isAiAnalysis() { return aiAnalysis; }
    public void setAiAnalysis(boolean aiAnalysis) { this.aiAnalysis = aiAnalysis; }
    public boolean isDiarize() { return diarize; }
    public void setDiarize(boolean diarize) { this.diarize = diarize; }
    public String getVideoPath() { return videoPath; }
    public void setVideoPath(String videoPath) { this.videoPath = videoPath; }
    public VideoStatus getVideoStatus() { return videoStatus; }
    public void setVideoStatus(VideoStatus videoStatus) { this.videoStatus = videoStatus; }
    public CorrectionStatus getCorrectionStatus() { return correctionStatus; }
    public void setCorrectionStatus(CorrectionStatus correctionStatus) { this.correctionStatus = correctionStatus; }
    public String getSummaryPrompt() { return summaryPrompt; }
    public void setSummaryPrompt(String summaryPrompt) { this.summaryPrompt = summaryPrompt; }
    public Integer getSummaryMaxWords() { return summaryMaxWords; }
    public void setSummaryMaxWords(Integer summaryMaxWords) { this.summaryMaxWords = summaryMaxWords; }
    public String getSummaryLanguage() { return summaryLanguage; }
    public void setSummaryLanguage(String summaryLanguage) { this.summaryLanguage = summaryLanguage; }
}
