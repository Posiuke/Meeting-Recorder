package bbbbot.auth.oidc;

import bbbbot.domain.AppUser;
import bbbbot.domain.OidcProvider;
import bbbbot.repository.Repositories.AppUserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ordnet die Claims eines geprueften ID-Tokens einem Recorder-Konto zu.
 *
 * <ul>
 *   <li>Wiedererkennung immer ueber Anbieter + {@code sub} - nie ueber Name oder
 *       E-Mail, die sich beim Anbieter aendern koennen.</li>
 *   <li>Erstanmeldung: Gibt es schon ein Konto mit dem Benutzernamen, das weder
 *       ein lokales Passwort hat noch an einem Anbieter haengt (typisch: ein
 *       frueheres LDAP-Konto mit demselben UPN), wird es uebernommen - so bleiben
 *       Aufnahmen und Freigaben beim Umstieg von AD auf Entra erhalten. Ein
 *       lokales Passwort-Konto wird bewusst nicht uebernommen: Wer beim Anbieter
 *       einen passenden Namen bekommt, darf damit kein fremdes Konto kapern.</li>
 *   <li>Admin-Recht: Ist {@code admin_values} gesetzt, folgt es bei jeder
 *       Anmeldung dem Anbieter (auch Entzug). Sonst bleibt es Sache des Recorders.</li>
 * </ul>
 */
@Component
public class OidcUserMapper {

    private static final Logger log = LoggerFactory.getLogger(OidcUserMapper.class);

    private final AppUserRepo userRepo;

    public OidcUserMapper(AppUserRepo userRepo) {
        this.userRepo = userRepo;
    }

    @Transactional
    public AppUser resolve(OidcProvider provider, Map<String, Object> claims) throws OidcException {
        String subject = string(claims.get("sub"));
        if (subject == null) throw new OidcException("invalid_token", "ID-Token ohne sub");

        List<String> groups = provider.getGroupsClaim() == null || provider.getGroupsClaim().isBlank()
                ? List.of() : OidcClient.asStrings(claims.get(provider.getGroupsClaim().trim()));
        Set<String> allowed = OidcProvider.splitValues(provider.getAllowedValues());
        if (!allowed.isEmpty() && groups.stream().noneMatch(allowed::contains)) {
            throw new OidcException("not_allowed",
                    "Anmeldung ueber " + provider.getSlug() + " ohne passende Gruppe/Rolle (sub " + subject + ")");
        }

        String username = username(provider, claims);
        if (username == null) {
            throw new OidcException("no_username",
                    "Kein Benutzername im Claim " + provider.getUsernameClaim() + " (sub " + subject + ")");
        }
        String displayName = firstNonBlank(string(claims.get("name")),
                join(string(claims.get("given_name")), string(claims.get("family_name"))));
        String email = string(claims.get("email"));

        AppUser user = userRepo.findByAuthProviderAndExternalSubject(provider.getSlug(), subject).orElse(null);
        if (user == null) {
            AppUser existing = userRepo.findByUsernameIgnoreCase(username).orElse(null);
            if (existing != null) {
                boolean hasPassword = existing.getPasswordHash() != null && !existing.getPasswordHash().isBlank();
                if (hasPassword || existing.getAuthProvider() != null) {
                    throw new OidcException("username_taken", "Benutzername " + username
                            + " gehoert bereits einem " + (hasPassword ? "lokalen Konto" : "Konto von "
                            + existing.getAuthProvider()));
                }
                user = existing;
                log.info("Konto {} wird kuenftig ueber {} angemeldet (bisher LDAP)", username, provider.getSlug());
            } else {
                user = AppUser.create(username, displayName == null ? username : displayName, email);
                log.info("Neues Konto {} ueber {} angelegt", username, provider.getSlug());
            }
            user.setAuthProvider(provider.getSlug());
            user.setExternalSubject(subject);
        }

        if (displayName != null) user.setDisplayName(displayName);
        if (email != null) user.setEmail(email);
        if (user.getDisplayName() == null || user.getDisplayName().isBlank()) user.setDisplayName(user.getUsername());

        Set<String> adminValues = OidcProvider.splitValues(provider.getAdminValues());
        if (!adminValues.isEmpty()) {
            boolean admin = groups.stream().anyMatch(adminValues::contains);
            if (admin != user.isAdmin()) {
                log.info("Admin-Recht von {} laut {}: {}", user.getUsername(), provider.getSlug(), admin);
                user.setAdmin(admin);
            }
        }
        user.setLastLoginAt(Instant.now());
        return userRepo.save(user);
    }

    /** Benutzername aus dem konfigurierten Claim, sonst E-Mail, zuletzt der Anbieter-Name + sub. */
    static String username(OidcProvider provider, Map<String, Object> claims) {
        String claim = provider.getUsernameClaim() == null || provider.getUsernameClaim().isBlank()
                ? "preferred_username" : provider.getUsernameClaim().trim();
        String value = firstNonBlank(string(claims.get(claim)), string(claims.get("email")));
        if (value == null) return null;
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private static String string(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + " " + b;
    }
}
