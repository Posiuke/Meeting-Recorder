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
@Table(name = "processing_job")
public class ProcessingJob {

    public enum Type { PROCESS }

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 32)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(nullable = false)
    private boolean immediate;

    /** Erneute Auswertung: nach Erfolg werden die alten Zusammenfassungen ersetzt. */
    @Column(nullable = false)
    private boolean replaceExisting;

    /** Erneute Transkription: vorhandene Segment-Transkripte werden neu erstellt. */
    @Column(nullable = false)
    private boolean redoTranscripts;

    /** Zwei-Schritt-Auswertung: nur transkribieren, keine Zusammenfassung erstellen. */
    @Column(nullable = false)
    private boolean transcribeOnly;

    @Column(nullable = false)
    private int attempts;

    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant startedAt;

    private Instant finishedAt;

    public static ProcessingJob create(UUID recordingId, boolean immediate) {
        ProcessingJob j = new ProcessingJob();
        j.id = UUID.randomUUID();
        j.recordingId = recordingId;
        j.type = Type.PROCESS;
        j.status = Status.PENDING;
        j.immediate = immediate;
        j.createdAt = Instant.now();
        return j;
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public boolean isImmediate() { return immediate; }
    public void setImmediate(boolean immediate) { this.immediate = immediate; }
    public boolean isReplaceExisting() { return replaceExisting; }
    public void setReplaceExisting(boolean replaceExisting) { this.replaceExisting = replaceExisting; }
    public boolean isRedoTranscripts() { return redoTranscripts; }
    public void setRedoTranscripts(boolean redoTranscripts) { this.redoTranscripts = redoTranscripts; }
    public boolean isTranscribeOnly() { return transcribeOnly; }
    public void setTranscribeOnly(boolean transcribeOnly) { this.transcribeOnly = transcribeOnly; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
