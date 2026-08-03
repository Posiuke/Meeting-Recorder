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
@Table(name = "summary")
public class Summary {

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @Column(columnDefinition = "text")
    private String markdown;

    private String model;

    private String error;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant finishedAt;

    public static Summary create(UUID recordingId) {
        Summary s = new Summary();
        s.id = UUID.randomUUID();
        s.recordingId = recordingId;
        s.status = Status.PENDING;
        s.createdAt = Instant.now();
        return s;
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getMarkdown() { return markdown; }
    public void setMarkdown(String markdown) { this.markdown = markdown; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
