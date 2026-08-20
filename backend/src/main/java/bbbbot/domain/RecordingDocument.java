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
 * Eine der Aufnahme beigefuegte Unterlage: Tagesordnung, Folien, ein Papier, das
 * in der Besprechung durchgesprochen wurde. Ihr Text geht in die KI-Auswertung
 * ein - damit kennt die Zusammenfassung das Thema und nicht nur das Gesprochene.
 *
 * <p>Der extrahierte Text steht hier und nicht nur in der Datei: Die Extraktion
 * laeuft einmal (bei einem Scan mit OCR dauert sie Minuten), gelesen wird sie bei
 * jeder Auswertung. Die Datei selbst bleibt daneben liegen und herunterladbar -
 * die Unterlage soll nachlesbar sein, nicht nur ihr Textauszug.
 */
@Entity
@Table(name = "recording_document")
public class RecordingDocument {

    /**
     * Zustand der Textextraktion.
     *
     * <p>{@code FAILED} bedeutet auch "kein Text herausgekommen": Ein Scan ohne
     * OCR liefert eine leere Antwort, und das ist ein Fehler, den der Nutzer sehen
     * muss - sonst waehnt er die Unterlage in der Auswertung.
     */
    public enum Status { PENDING, READY, FAILED }

    public static final int MAX_FILENAME_LENGTH = 255;

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    @Column(nullable = false, length = MAX_FILENAME_LENGTH)
    private String filename;

    @Column(nullable = false, columnDefinition = "text")
    private String storedPath;

    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(columnDefinition = "text")
    private String extractedText;

    private Integer textChars;

    @Column(columnDefinition = "text")
    private String error;

    private UUID uploadedBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant extractedAt;

    /**
     * Neue Unterlage. Ablageort und Groesse folgen erst danach: Der Dateiname im
     * Verzeichnis enthaelt die Kennung dieser Zeile, und die entsteht hier.
     */
    public static RecordingDocument create(UUID recordingId, String filename, String contentType,
                                           UUID uploadedBy) {
        RecordingDocument doc = new RecordingDocument();
        doc.id = UUID.randomUUID();
        doc.recordingId = recordingId;
        doc.filename = filename;
        doc.contentType = contentType;
        doc.uploadedBy = uploadedBy;
        doc.status = Status.PENDING;
        doc.createdAt = Instant.now();
        return doc;
    }

    /** Endung in Kleinschreibung, ohne Punkt ("" wenn keine da ist). */
    public String extension() {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Liegt verwertbarer Text vor? Nur so eine Unterlage geht in den Prompt. */
    public boolean isUsable() {
        return status == Status.READY && extractedText != null && !extractedText.isBlank();
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public String getFilename() { return filename; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public Integer getTextChars() { return textChars; }
    public void setTextChars(Integer textChars) { this.textChars = textChars; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExtractedAt() { return extractedAt; }
    public void setExtractedAt(Instant extractedAt) { this.extractedAt = extractedAt; }
}
