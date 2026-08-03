package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "recording_segment")
public class RecordingSegment {

    public enum Status { RECORDING, TRANSCODING, READY, EMPTY, FAILED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    private String webmPath;

    @Column(name = "mp3_path")
    private String mp3Path;

    private Long sizeBytes;

    private Long durationMs;

    @Column(columnDefinition = "text")
    private String transcriptText;

    /**
     * Geglaettete Fassung von {@link #transcriptText} (Fuellwoerter, Satzzeichen,
     * Erkennungsfehler bereinigt). Das Original bleibt immer erhalten - im
     * Frontend laesst sich zwischen beiden umschalten. {@code null} = nicht geglaettet.
     */
    @Column(columnDefinition = "text")
    private String correctedText;

    private String error;

    public static RecordingSegment create(UUID recordingId, int seq, String webmPath) {
        RecordingSegment s = new RecordingSegment();
        s.id = UUID.randomUUID();
        s.recordingId = recordingId;
        s.seq = seq;
        s.webmPath = webmPath;
        s.status = Status.RECORDING;
        return s;
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public int getSeq() { return seq; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getWebmPath() { return webmPath; }
    public void setWebmPath(String webmPath) { this.webmPath = webmPath; }
    public String getMp3Path() { return mp3Path; }
    public void setMp3Path(String mp3Path) { this.mp3Path = mp3Path; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getTranscriptText() { return transcriptText; }
    public void setTranscriptText(String transcriptText) { this.transcriptText = transcriptText; }
    public String getCorrectedText() { return correctedText; }
    public void setCorrectedText(String correctedText) { this.correctedText = correctedText; }

    /** Die fuer Auswertung und Anzeige bevorzugte Fassung: geglaettet, sonst Original. */
    public String getEffectiveTranscript() {
        return correctedText != null && !correctedText.isBlank() ? correctedText : transcriptText;
    }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
