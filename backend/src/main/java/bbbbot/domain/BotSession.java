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
@Table(name = "bot_session")
public class BotSession {

    public enum Status { STARTING, JOINED, RECORDING, RECONNECTING, STOPPED, FAILED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String meetingUrl;

    @Column(name = "bot_name", nullable = false)
    private String botName;

    /** Vom Bot aus der BBB-Oberflaeche erkannter Raum-/Meetingname. */
    @Column(name = "room_name", length = 512)
    private String roomName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant endedAt;

    private String lastError;

    @Column(nullable = false)
    private boolean autoRecord = true;

    @Column(nullable = false)
    private boolean recordVideo = false;

    @Column(nullable = false)
    private boolean aiAnalysis = true;

    /** Sprechererkennung gewuenscht (greift nur, wenn der Admin sie freigeschaltet hat). */
    @Column(nullable = false)
    private boolean diarize = false;

    /**
     * Sprache der Spracherkennung fuer die Aufnahmen dieser Session (null =
     * Admin-Standard, "auto" = Whisper erkennt die Sprache selbst). Wird beim
     * Aufnahmestart an die Aufnahme weitergegeben.
     */
    @Column(length = 16)
    private String sttLanguage;

    public static BotSession create(String meetingUrl, String botName, UUID createdBy, boolean autoRecord,
                                    boolean recordVideo, boolean aiAnalysis, boolean diarize) {
        BotSession s = new BotSession();
        s.id = UUID.randomUUID();
        s.meetingUrl = meetingUrl;
        s.botName = botName;
        s.createdBy = createdBy;
        s.status = Status.STARTING;
        s.createdAt = Instant.now();
        s.autoRecord = autoRecord;
        s.recordVideo = recordVideo;
        s.aiAnalysis = aiAnalysis;
        s.diarize = diarize;
        return s;
    }

    public UUID getId() { return id; }
    public String getMeetingUrl() { return meetingUrl; }
    public String getBotName() { return botName; }
    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public boolean isAutoRecord() { return autoRecord; }
    public void setAutoRecord(boolean autoRecord) { this.autoRecord = autoRecord; }
    public boolean isRecordVideo() { return recordVideo; }
    public void setRecordVideo(boolean recordVideo) { this.recordVideo = recordVideo; }
    public boolean isAiAnalysis() { return aiAnalysis; }
    public void setAiAnalysis(boolean aiAnalysis) { this.aiAnalysis = aiAnalysis; }
    public boolean isDiarize() { return diarize; }
    public void setDiarize(boolean diarize) { this.diarize = diarize; }
    public String getSttLanguage() { return sttLanguage; }
    public void setSttLanguage(String sttLanguage) { this.sttLanguage = sttLanguage; }
}
