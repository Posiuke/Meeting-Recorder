package bbbbot.auth;

import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.settings.AuthSettingsService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Anmeldung gegen ein lokales Passwort-Konto (bcrypt in der DB) ODER - falls im
 * Admin-Bereich aktiviert - gegen Active Directory. Ein lokales Konto (z.B. der
 * Admin) funktioniert immer, damit man sich zum Konfigurieren/Testen von LDAP
 * nie aussperrt.
 */
@Service
public class AuthService {

    private final AppUserRepo userRepo;
    private final JwtService jwtService;
    private final PasswordEncoder encoder;
    private final AuthSettingsService authSettings;
    private final LdapAuthenticator ldap;

    public AuthService(AppUserRepo userRepo, JwtService jwtService, PasswordEncoder encoder,
                       AuthSettingsService authSettings, LdapAuthenticator ldap) {
        this.userRepo = userRepo;
        this.jwtService = jwtService;
        this.encoder = encoder;
        this.authSettings = authSettings;
        this.ldap = ldap;
    }

    public record LoginResult(String token, AppUser user) {}

    @Transactional
    public LoginResult login(String username, String password) {
        AppUser local = userRepo.findByUsernameIgnoreCase(username).orElse(null);

        // 1. Lokales Passwort-Konto: eigenstaendig, unabhaengig vom LDAP-Status.
        if (local != null && local.getPasswordHash() != null && !local.getPasswordHash().isBlank()) {
            if (!encoder.matches(password, local.getPasswordHash())) {
                throw new BadCredentialsException("Anmeldung fehlgeschlagen");
            }
            local.setLastLoginAt(Instant.now());
            userRepo.save(local);
            return new LoginResult(jwtService.issue(local.getUsername()), local);
        }

        // 2. LDAP/AD - nur wenn aktiviert.
        if (authSettings.isLdapEnabled()) {
            LdapAuthenticator.LdapUser ad;
            try {
                ad = ldap.authenticate(username, password);
            } catch (BadCredentialsException e) {
                throw e;
            } catch (RuntimeException e) {
                // LDAP nicht konfiguriert (IllegalState), Server nicht erreichbar
                // (CommunicationException) o.ae. -> als Fehlanmeldung behandeln.
                throw new BadCredentialsException("Anmeldung fehlgeschlagen");
            }
            String canonical = ad.username();
            AppUser user = userRepo.findByUsernameIgnoreCase(canonical)
                    .orElseGet(() -> userRepo.save(AppUser.create(canonical, canonical, null)));
            if (ad.displayName() != null) user.setDisplayName(ad.displayName());
            if (ad.email() != null) user.setEmail(ad.email());
            if (user.getDisplayName() == null || user.getDisplayName().isBlank()) {
                user.setDisplayName(canonical);
            }
            if (authSettings.bootstrapAdmins().contains(canonical.toLowerCase()) && !user.isAdmin()) {
                user.setAdmin(true);
            }
            user.setLastLoginAt(Instant.now());
            userRepo.save(user);
            return new LoginResult(jwtService.issue(user.getUsername()), user);
        }

        throw new BadCredentialsException("Anmeldung fehlgeschlagen");
    }

    /** Aendert das lokale Passwort des angemeldeten Nutzers und liefert den aktualisierten Datensatz. */
    @Transactional
    public AppUser changePassword(AppUser user, String currentPassword, String newPassword) {
        AppUser fresh = userRepo.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("Nutzer nicht gefunden"));
        if (fresh.getPasswordHash() == null || fresh.getPasswordHash().isBlank()) {
            throw new IllegalStateException(
                    "Fuer dieses Konto ist keine lokale Passwort-Anmeldung eingerichtet (LDAP-Konto).");
        }
        if (currentPassword == null || !encoder.matches(currentPassword, fresh.getPasswordHash())) {
            throw new BadCredentialsException("Aktuelles Passwort ist falsch");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Neues Passwort muss mindestens 8 Zeichen haben");
        }
        fresh.setPasswordHash(encoder.encode(newPassword));
        fresh.setMustChangePassword(false);
        return userRepo.save(fresh);
    }
}
