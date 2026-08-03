package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Eintrag im persoenlichen Glossar: eine Abkuerzung oder ein Fachbegriff, der in
 * den eigenen Besprechungen vorkommt, optional mit Bedeutung. Die Eintraege
 * gehen in die KI-Glaettung des Transkripts ein, damit haus- und fachinterne
 * Begriffe richtig geschrieben und nicht "wegkorrigiert" werden.
 *
 * <p>Jeder Nutzer pflegt seine eigene Liste; verwendet wird bei einer Aufnahme
 * das Glossar ihres Besitzers.
 */
@Entity
@Table(name = "glossary_entry")
public class GlossaryEntry {

    public static final int MAX_TERM_LENGTH = 200;
    /** Bedeutung liegt in einer TEXT-Spalte - reichlich Platz fuer Erklaerungen. */
    public static final int MAX_MEANING_LENGTH = 4000;

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 200)
    private String term;

    @Column(nullable = false, length = 200)
    private String termKey;

    @Column(columnDefinition = "text")
    private String meaning;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    public static GlossaryEntry create(UUID ownerId, String term, String meaning) {
        GlossaryEntry entry = new GlossaryEntry();
        entry.id = UUID.randomUUID();
        entry.ownerId = ownerId;
        entry.setTerm(term);
        entry.meaning = meaning;
        entry.createdAt = Instant.now();
        return entry;
    }

    /** Vergleichsform eines Begriffs (Rand-Leerzeichen weg, Kleinschreibung). */
    public static String normalizeKey(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.GERMAN);
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getTerm() { return term; }

    public void setTerm(String term) {
        this.term = term == null ? "" : term.strip();
        this.termKey = normalizeKey(term);
    }

    public String getTermKey() { return termKey; }
    public String getMeaning() { return meaning; }
    public void setMeaning(String meaning) { this.meaning = meaning; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
