package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Freigabe-Link einer Aufnahme. Zwei Arten:
 *
 * <ul>
 *   <li>{@code requireLogin = true} (Standard): Der Empfaenger muss sich
 *       anmelden; beim Oeffnen wird die Aufnahme automatisch mit seinem Konto
 *       geteilt. Datenschutzfreundlich, weil jeder Zugriff einem Konto zuzuordnen
 *       ist und die Freigabe in der Liste des Besitzers auftaucht.</li>
 *   <li>{@code requireLogin = false}: Wer die Adresse kennt, sieht Video, Audio,
 *       Transkript und Zusammenfassung ohne Anmeldung - fuer Empfaenger ohne
 *       Zugang zum System.</li>
 * </ul>
 *
 * <p>Das Token liegt bewusst im Klartext in der Datenbank (anders als bei
 * {@link ApiKey}): Der Besitzer soll den Link auch spaeter noch kopieren
 * koennen. Es ist ein reines Zugriffsmerkmal fuer genau diese eine Aufnahme -
 * mehr Rechte haengen nicht daran, und wer die Datenbank lesen kann, kommt an
 * die Aufnahmen ohnehin heran.
 */
@Entity
@Table(name = "share_link")
public class ShareLink {

    /** 32 Byte Zufall, base64url kodiert - nicht erratbar. */
    public static final int TOKEN_LENGTH = 43;

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    @Column(nullable = false, length = 64, unique = true)
    private String token;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    /** NULL = gueltig bis zum Widerruf. */
    private Instant expiresAt;

    /**
     * Anmeldung noetig? Der Standard ist bewusst {@code true}: Ein Link ohne
     * Anmeldung entsteht nur, wenn der Besitzer ihn ausdruecklich waehlt.
     */
    @Column(nullable = false)
    private boolean requireLogin = true;

    private Instant lastViewedAt;

    @Column(nullable = false)
    private int views = 0;

    public static ShareLink create(UUID recordingId, String token, UUID createdBy, Instant expiresAt,
                                  boolean requireLogin) {
        ShareLink link = new ShareLink();
        link.id = UUID.randomUUID();
        link.recordingId = recordingId;
        link.token = token;
        link.createdBy = createdBy;
        link.createdAt = Instant.now();
        link.expiresAt = expiresAt;
        link.requireLogin = requireLogin;
        return link;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public String getToken() { return token; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRequireLogin() { return requireLogin; }
    public Instant getLastViewedAt() { return lastViewedAt; }
    public void setLastViewedAt(Instant lastViewedAt) { this.lastViewedAt = lastViewedAt; }
    public int getViews() { return views; }
    public void setViews(int views) { this.views = views; }
}
