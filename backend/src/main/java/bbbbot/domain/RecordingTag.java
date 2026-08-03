package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Schlagwort einer Aufnahme ("Projekt Nord", "Protokoll"). Gehoert zur Aufnahme,
 * nicht zum Nutzer: Der Besitzer pflegt die Schlagworte, alle mit
 * Leseberechtigung sehen sie und koennen danach suchen.
 *
 * <p>{@code name} haelt die Schreibweise des Nutzers, {@code nameKey} die
 * normalisierte Kleinschreibung - damit sind "Projekt Nord" und "projekt nord"
 * dasselbe Schlagwort, und die Suche muss nicht auf Gross-/Kleinschreibung achten.
 */
@Entity
@Table(name = "recording_tag")
public class RecordingTag {

    /** Laengengrenze fuer ein Schlagwort (auch in der Spaltendefinition). */
    public static final int MAX_LENGTH = 40;

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 64)
    private String nameKey;

    @Column(nullable = false)
    private Instant createdAt;

    public static RecordingTag create(UUID recordingId, String name) {
        RecordingTag tag = new RecordingTag();
        tag.id = UUID.randomUUID();
        tag.recordingId = recordingId;
        tag.name = name;
        tag.nameKey = normalizeKey(name);
        tag.createdAt = Instant.now();
        return tag;
    }

    /**
     * Anzeigeform bereinigen: Rand-Leerzeichen weg, Mehrfach-Leerzeichen zu
     * einem zusammenziehen. Gibt einen leeren String zurueck, wenn nichts bleibt.
     */
    public static String normalizeName(String raw) {
        if (raw == null) return "";
        return raw.strip().replaceAll("\\s+", " ");
    }

    /** Vergleichsform: normalisierte Anzeigeform in Kleinbuchstaben. */
    public static String normalizeKey(String raw) {
        return normalizeName(raw).toLowerCase(Locale.GERMAN);
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public String getName() { return name; }
    public String getNameKey() { return nameKey; }
    public Instant getCreatedAt() { return createdAt; }
}
