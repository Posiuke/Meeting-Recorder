package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Oeffentlicher Freigabe-Link einer Aufnahme: Wer die Adresse kennt, sieht
 * Video, Audio, Transkript und Zusammenfassung, ohne sich anzumelden.
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

    private Instant lastViewedAt;

    @Column(nullable = false)
    private int views = 0;

    public static ShareLink create(UUID recordingId, String token, UUID createdBy, Instant expiresAt) {
        ShareLink link = new ShareLink();
        link.id = UUID.randomUUID();
        link.recordingId = recordingId;
        link.token = token;
        link.createdBy = createdBy;
        link.createdAt = Instant.now();
        link.expiresAt = expiresAt;
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
    public Instant getLastViewedAt() { return lastViewedAt; }
    public void setLastViewedAt(Instant lastViewedAt) { this.lastViewedAt = lastViewedAt; }
    public int getViews() { return views; }
    public void setViews(int views) { this.views = views; }
}
