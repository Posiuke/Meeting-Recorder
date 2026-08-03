package bbbbot.auth;

import bbbbot.config.AppProperties;
import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.settings.AuthSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Einmalige Erstbefuellung beim Start:
 *  - initialisiert die Auth-Konfiguration in der DB, falls noch nichts hinterlegt
 *    ist (Standard: LDAP aus - wird spaeter im Admin-Bereich aktiviert/konfiguriert);
 *  - legt ein lokales Admin-Konto mit Initialpasswort an, falls es fehlt.
 */
@Component
public class AuthBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrap.class);

    private final AppProperties props;
    private final AuthSettingsService authSettings;
    private final AppUserRepo userRepo;
    private final PasswordEncoder encoder;

    public AuthBootstrap(AppProperties props, AuthSettingsService authSettings,
                         AppUserRepo userRepo, PasswordEncoder encoder) {
        this.props = props;
        this.authSettings = authSettings;
        this.userRepo = userRepo;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Auth auth = props.getAuth();

        // Auth-Config beim allerersten Start initialisieren. Standard: LDAP aus -
        // die vollstaendige LDAP-Konfiguration erfolgt im Admin-Bereich.
        authSettings.seedIfAbsent(AuthSettingsService.LDAP_ENABLED, "false");
        authSettings.seedIfAbsent(AuthSettingsService.LDAP_DOMAIN, "");
        authSettings.seedIfAbsent(AuthSettingsService.LDAP_URL, "");
        authSettings.seedIfAbsent(AuthSettingsService.LDAP_ROOT_DN, "");
        authSettings.seedIfAbsent(AuthSettingsService.BOOTSTRAP_ADMINS, "");

        // Lokales Admin-Konto sicherstellen.
        String adminUsername = auth.getAdminUsername() == null || auth.getAdminUsername().isBlank()
                ? "admin" : auth.getAdminUsername().trim();
        if (userRepo.findByUsernameIgnoreCase(adminUsername).isEmpty()) {
            String initial = auth.getAdminInitialPassword() == null || auth.getAdminInitialPassword().isBlank()
                    ? "admin" : auth.getAdminInitialPassword();
            AppUser admin = AppUser.create(adminUsername, adminUsername, null);
            admin.setAdmin(true);
            admin.setPasswordHash(encoder.encode(initial));
            admin.setMustChangePassword(true);
            userRepo.save(admin);
            log.warn("Lokaler Admin '{}' angelegt. Initialpasswort MUSS beim ersten Login geaendert werden.",
                    adminUsername);
        }
    }
}
