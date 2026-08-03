package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Teilnehmer einer Aufnahme. Wird nach der Transkription aus den erkannten
 * Diarisierungs-Sprechern angelegt (speakerLabel = z.B. SPEAKER_00) und kann
 * vom Besitzer umbenannt werden - der Anzeigename ersetzt das Label dann
 * ueberall im Transkript und in neuen Zusammenfassungen.
 */
@Entity
@Table(name = "participant")
public class Participant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    /** Diarisierungs-Label aus der Transkription (z.B. SPEAKER_00). */
    private String speakerLabel;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private Instant createdAt;

    public static Participant forSpeaker(UUID recordingId, String speakerLabel, String displayName) {
        Participant p = new Participant();
        p.id = UUID.randomUUID();
        p.recordingId = recordingId;
        p.speakerLabel = speakerLabel;
        p.displayName = displayName;
        p.createdAt = Instant.now();
        return p;
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public String getSpeakerLabel() { return speakerLabel; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Instant getCreatedAt() { return createdAt; }
}
