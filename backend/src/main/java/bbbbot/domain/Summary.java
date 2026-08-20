package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Fassung der Zusammenfassung einer Aufnahme. Jede Auswertung legt eine
 * weitere Fassung an, statt die vorhandene zu ueberschreiben: Sonst waere eine
 * von Hand ueberarbeitete Fassung mit dem naechsten Versuch verloren, und ob
 * eine neue Vorlage die bessere Zusammenfassung liefert, liesse sich nicht
 * nachsehen.
 *
 * <p>Genau eine Fassung je Aufnahme ist die {@link #isCurrent() aktuelle} - sie
 * steht in summary.md, im Download, in der API und in der Freigabe-Ansicht. Die
 * uebrigen bleiben zum Vergleich daneben und koennen einzeln geloescht werden.
 */
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

    /**
     * Temperatur dieser Fassung. Gehoert zum Modell: Zwei Fassungen desselben
     * Modells sind sonst nicht auseinanderzuhalten.
     */
    private Double temperature;

    /** Name der Vorlage, mit der diese Fassung erzeugt wurde (null = keine benannte Vorlage). */
    @Column(length = 200)
    private String templateName;

    /**
     * Der Auswertungs-Prompt dieser Fassung. Er steht hier und nicht nur an der
     * Aufnahme, weil beim Vergleich zweier Fassungen genau die Frage ist, womit
     * sie entstanden sind - und der Prompt der Aufnahme sich bis zur naechsten
     * Auswertung geaendert haben kann.
     */
    @Column(columnDefinition = "text")
    private String systemPrompt;

    private String error;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant finishedAt;

    /** Zeitpunkt der letzten haendischen Bearbeitung (null = unveraendert vom Modell). */
    private Instant editedAt;

    /** Die eine Fassung, die ueberall als "die" Zusammenfassung gilt. */
    @Column(name = "is_current", nullable = false)
    private boolean current;

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
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }

    /** Eine Fassung mit Inhalt - nur so eine kann die aktuelle sein. */
    public boolean isUsable() {
        return status == Status.DONE && markdown != null && !markdown.isBlank();
    }
}
