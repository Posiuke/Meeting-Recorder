package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    private String displayName;

    private String email;

    @Column(name = "is_admin", nullable = false)
    private boolean admin;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastLoginAt;

    /**
     * Letzte Aktivitaet im Frontend (angefragter Endpunkt mit gueltigem Token).
     * Grundlage fuer "wer ist gerade angemeldet" im Admin-Bereich - die
     * Anmeldung selbst ist zustandslos (JWT), es gibt also keine Sitzung, die
     * man zaehlen koennte. Wird gedrosselt geschrieben, siehe UserActivityService.
     */
    private Instant lastSeenAt;

    /** bcrypt-Hash fuer lokale Anmeldung; NULL bei reinen LDAP-Konten. */
    private String passwordHash;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * Gewaehlte Oberflaechensprache (z.B. "de", "en"). NULL = noch nicht gewaehlt;
     * das Frontend nimmt dann die Browsersprache.
     */
    @Column(length = 8)
    private String language;

    public static AppUser create(String username, String displayName, String email) {
        AppUser u = new AppUser();
        u.id = UUID.randomUUID();
        u.username = username;
        u.displayName = displayName;
        u.email = email;
        u.createdAt = Instant.now();
        return u;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isAdmin() { return admin; }
    public void setAdmin(boolean admin) { this.admin = admin; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
}
