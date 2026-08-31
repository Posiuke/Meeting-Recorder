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

    /**
     * Bei Anlage des Auftrags lag schon eine fertige Zusammenfassung vor. Alte
     * Fassungen werden nicht mehr ersetzt (jede Auswertung legt eine weitere
     * Fassung daneben) - die Angabe entscheidet nur noch, ob eine erneute
     * Transkription bei Schritt 1 stehen bleibt.
     */
    @Column(nullable = false)
    private boolean hadSummary;

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

    /**
     * Dauer der einzelnen Schritte in Millisekunden; {@code null} = dieser
     * Schritt lief in diesem Auftrag nicht (eine reine Transkription hat keine
     * Zusammenfassung, eine erneute Auswertung keinen Whisper-Lauf, und die
     * Glaettung kann abgeschaltet sein).
     *
     * <p>Die Gesamtdauer steht schon in {@code startedAt}/{@code finishedAt} -
     * fuer die Betriebsfrage "war Whisper langsam oder das LLM?" reicht sie
     * nicht.
     */
    private Long sttMs;

    private Long correctionMs;

    private Long summaryMs;

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
    public boolean isHadSummary() { return hadSummary; }
    public void setHadSummary(boolean hadSummary) { this.hadSummary = hadSummary; }
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
    public Long getSttMs() { return sttMs; }
    public void setSttMs(Long sttMs) { this.sttMs = sttMs; }
    public Long getCorrectionMs() { return correctionMs; }
    public void setCorrectionMs(Long correctionMs) { this.correctionMs = correctionMs; }
    public Long getSummaryMs() { return summaryMs; }
    public void setSummaryMs(Long summaryMs) { this.summaryMs = summaryMs; }

    /**
     * Kurzform dessen, was dieser Auftrag tut - fuer die Admin-Uebersicht.
     * Bewusst als ein Wert und nicht als drei Schalter: In einer Tabellenzeile
     * will man einen Begriff lesen, nicht drei Haken deuten.
     */
    public String mode() {
        if (redoTranscripts) return transcribeOnly ? "RETRANSCRIBE_ONLY" : "RETRANSCRIBE";
        if (transcribeOnly) return "TRANSCRIBE_ONLY";
        if (hadSummary) return "REPROCESS";
        return "FULL";
    }

    /** Gesamtdauer des letzten Laufs; null, solange er nicht fertig ist. */
    public Long durationMs() {
        if (startedAt == null || finishedAt == null) return null;
        return java.time.Duration.between(startedAt, finishedAt).toMillis();
    }
}
