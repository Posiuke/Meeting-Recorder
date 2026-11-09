package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Ein OpenID-Connect-Anbieter fuer die Anmeldung (siehe V32__oidc_login.sql). */
@Entity
@Table(name = "oidc_provider")
public class OidcProvider {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "issuer_uri", nullable = false, length = 500)
    private String issuerUri;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "client_secret", length = 1000)
    private String clientSecret;

    @Column(nullable = false)
    private String scopes = "openid profile email";

    @Column(name = "username_claim", nullable = false, length = 100)
    private String usernameClaim = "preferred_username";

    @Column(name = "groups_claim", length = 100)
    private String groupsClaim;

    @Column(name = "admin_values", length = 1000)
    private String adminValues;

    @Column(name = "allowed_values", length = 1000)
    private String allowedValues;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static OidcProvider create(String slug) {
        OidcProvider p = new OidcProvider();
        p.id = UUID.randomUUID();
        p.slug = slug;
        p.createdAt = Instant.now();
        return p;
    }

    /** Kommagetrennte Liste als Menge (getrimmt, ohne Leereintraege). */
    public static Set<String> splitValues(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split("[,\\n]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public String getUsernameClaim() { return usernameClaim; }
    public void setUsernameClaim(String usernameClaim) { this.usernameClaim = usernameClaim; }
    public String getGroupsClaim() { return groupsClaim; }
    public void setGroupsClaim(String groupsClaim) { this.groupsClaim = groupsClaim; }
    public String getAdminValues() { return adminValues; }
    public void setAdminValues(String adminValues) { this.adminValues = adminValues; }
    public String getAllowedValues() { return allowedValues; }
    public void setAllowedValues(String allowedValues) { this.allowedValues = allowedValues; }
    public Instant getCreatedAt() { return createdAt; }
}
