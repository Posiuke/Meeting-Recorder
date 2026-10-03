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

    /** Mindestlaenge fuer lokale Passwoerter (Anlage, Reset, eigener Wechsel). */
    public static final int MIN_PASSWORD_LENGTH = 8;

    /** Erlaubte Benutzernamen: keine Leerzeichen, nichts, was in Pfaden/Logs stoert. */
    private static final java.util.regex.Pattern USERNAME = java.util.regex.Pattern.compile("[A-Za-z0-9._@-]{2,64}");

    /**
     * Legt ein lokales Konto an (Anmeldung mit Passwort aus der DB, unabhaengig
     * von LDAP). Das Initialpasswort vergibt der Admin; beim ersten Login muss
     * der Nutzer es aendern - wie beim lokalen Admin-Konto.
     *
     * @throws IllegalArgumentException bei ungueltigen Angaben
     * @throws IllegalStateException wenn der Benutzername schon vergeben ist
     *         (auch an ein LDAP-Konto - sonst kaemen sich beide Anmeldungen in die Quere)
     */
    @Transactional
    public AppUser createLocalUser(String username, String displayName, String email,
                                   String initialPassword, boolean admin) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Benutzername: 2-64 Zeichen, nur Buchstaben, Ziffern und . _ @ -");
        }
        if (userRepo.findByUsernameIgnoreCase(name).isPresent()) {
            throw new IllegalStateException("Benutzername ist bereits vergeben: " + name);
        }
        requireValidPassword(initialPassword);
        String display = displayName == null || displayName.isBlank() ? name : displayName.trim();
        String mail = email == null || email.isBlank() ? null : email.trim();
        if (display.length() > 200 || (mail != null && mail.length() > 200)) {
            throw new IllegalArgumentException("Name oder E-Mail ist zu lang (max. 200 Zeichen)");
        }
        AppUser user = AppUser.create(name, display, mail);
        user.setAdmin(admin);
        user.setPasswordHash(encoder.encode(initialPassword));
        user.setMustChangePassword(true);
        return userRepo.save(user);
    }

    /**
     * Setzt das Passwort eines lokalen Kontos durch einen Admin neu (vergessenes
     * Passwort). Der Nutzer muss es beim naechsten Login wieder aendern.
     */
    @Transactional
    public AppUser resetPassword(java.util.UUID userId, String newPassword) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Nutzer nicht gefunden"));
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new IllegalStateException("LDAP-Konto - das Passwort wird im Verzeichnisdienst verwaltet.");
        }
        requireValidPassword(newPassword);
        user.setPasswordHash(encoder.encode(newPassword));
        user.setMustChangePassword(true);
        return userRepo.save(user);
    }

    private static void requireValidPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Passwort muss mindestens " + MIN_PASSWORD_LENGTH + " Zeichen haben");
        }
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
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Neues Passwort muss mindestens " + MIN_PASSWORD_LENGTH + " Zeichen haben");
        }
        fresh.setPasswordHash(encoder.encode(newPassword));
        fresh.setMustChangePassword(false);
        return userRepo.save(fresh);
    }
}
