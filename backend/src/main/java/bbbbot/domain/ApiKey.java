package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persoenlicher API-Schluessel: erlaubt Skripten und fremden Programmen dieselben
 * Aufrufe, die der Nutzer auch in der Weboberfläche machen kann - ohne Passwort
 * und ohne kurzlebiges Login-Token.
 *
 * <p>Gespeichert wird nur der SHA-256-Abdruck des Schluessels, nie der Schluessel
 * selbst: Er wird beim Anlegen einmal angezeigt und ist danach nicht mehr
 * rekonstruierbar. Bewusst SHA-256 und nicht bcrypt - ein Schluessel besteht aus
 * 256 zufaelligen Bit, gegen die ein Woerterbuchangriff sinnlos ist, und der
 * Abdruck wird bei JEDEM API-Aufruf berechnet.
 *
 * <p>{@code readOnly} beschraenkt den Schluessel auf lesende Zugriffe (GET) -
 * damit kann ein Auswerte-Skript nichts veraendern oder loeschen. Die Rechte des
 * Nutzers bleiben in jedem Fall die Obergrenze.
 */
@Entity
@Table(name = "api_key")
public class ApiKey {

    public static final int MAX_NAME_LENGTH = 120;

    /** Erkennungszeichen am Anfang jedes Schluessels - macht Funde in Logs eindeutig. */
    public static final String TOKEN_PREFIX = "bbb_";

    /** So viele Zeichen des Schluessels werden zum Wiedererkennen gespeichert. */
    public static final int VISIBLE_PREFIX_LENGTH = 12;

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID ownerId;

    /** Frei gewaehlter Name, damit der Nutzer weiss, welches Skript den Schluessel nutzt. */
    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    /** Anfang des Schluessels ("bbb_A1b2C3d4") zur Anzeige in der Liste. */
    @Column(nullable = false, length = 24)
    private String tokenPrefix;

    @Column(nullable = false)
    private boolean readOnly;

    @Column(nullable = false)
    private Instant createdAt;

    /** Ablaufdatum; {@code null} bedeutet unbegrenzt gueltig. */
    private Instant expiresAt;

    /** Letzte Nutzung - damit vergessene Schluessel erkennbar sind. */
    private Instant lastUsedAt;

    public static ApiKey create(UUID ownerId, String name, String tokenHash, String tokenPrefix,
                               boolean readOnly, Instant expiresAt) {
        ApiKey key = new ApiKey();
        key.id = UUID.randomUUID();
        key.ownerId = ownerId;
        key.name = name;
        key.tokenHash = tokenHash;
        key.tokenPrefix = tokenPrefix;
        key.readOnly = readOnly;
        key.expiresAt = expiresAt;
        key.createdAt = Instant.now();
        return key;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getTokenHash() { return tokenHash; }
    public String getTokenPrefix() { return tokenPrefix; }
    public boolean isReadOnly() { return readOnly; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
