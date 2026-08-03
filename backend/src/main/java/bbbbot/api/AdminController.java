package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.auth.LdapAuthenticator;
import bbbbot.domain.AppUser;
import bbbbot.llm.LlmClient;
import bbbbot.media.FfmpegService;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.settings.AuthSettingsService;
import bbbbot.settings.SettingsService;
import bbbbot.stt.WhisperClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin-Funktionen: Einstellungen (STT/LLM/Zeitfenster/Bot), Authentifizierung und Admin-Rollen. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SettingsService settings;
    private final AuthSettingsService authSettings;
    private final LdapAuthenticator ldap;
    private final AppUserRepo userRepo;
    private final LlmClient llm;
    private final WhisperClient whisper;
    private final FfmpegService ffmpeg;

    public AdminController(SettingsService settings, AuthSettingsService authSettings,
                           LdapAuthenticator ldap, AppUserRepo userRepo,
                           LlmClient llm, WhisperClient whisper, FfmpegService ffmpeg) {
        this.settings = settings;
        this.authSettings = authSettings;
        this.ldap = ldap;
        this.userRepo = userRepo;
        this.llm = llm;
        this.whisper = whisper;
        this.ffmpeg = ffmpeg;
    }

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        return settings.getAll();
    }

    @PutMapping("/settings")
    public Map<String, String> updateSettings(@RequestBody Map<String, String> changes) {
        try {
            settings.update(changes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return settings.getAll();
    }

    @GetMapping("/settings/defaults")
    public Map<String, String> getDefaults() {
        return SettingsService.defaults();
    }

    // ------------------------------------------------------ Verbindungstests

    /**
     * Testet die gespeicherte LLM-Konfiguration mit einem minimalen Chat-Aufruf.
     * Prueft damit Erreichbarkeit, API-Key und Modellnamen in einem Rutsch.
     */
    @PostMapping("/settings/test-llm")
    public Dtos.ConnectionTestResult testLlm() {
        long start = System.currentTimeMillis();
        LlmClient.LlmResult result = llm.chat(
                "Du bist ein Verbindungstest. Antworte mit genau einem Wort.",
                "Antworte mit: OK");
        long duration = System.currentTimeMillis() - start;
        if (result.success()) {
            return new Dtos.ConnectionTestResult(true,
                    "LLM erreichbar - Modell \"" + settings.get(SettingsService.LLM_MODEL)
                            + "\" hat geantwortet (" + duration + " ms)", duration);
        }
        return new Dtos.ConnectionTestResult(false, result.error(), duration);
    }

    /**
     * Testet die gespeicherte Whisper-Konfiguration: eine kurze stille MP3 wird
     * erzeugt und wie ein echtes Segment transkribiert (inkl. API-Key/Modell
     * beim Cloud-Anbieter).
     */
    @PostMapping("/settings/test-whisper")
    public Dtos.ConnectionTestResult testWhisper() {
        java.nio.file.Path testFile = null;
        long start = System.currentTimeMillis();
        try {
            testFile = java.nio.file.Files.createTempFile("whisper-connection-test", ".mp3");
            if (!ffmpeg.createSilentMp3(testFile, 2)) {
                return new Dtos.ConnectionTestResult(false,
                        "Test-Audio konnte nicht erzeugt werden (ffmpeg pruefen)", 0);
            }
            WhisperClient.TranscriptionResult result = whisper.transcribe(testFile, false);
            long duration = System.currentTimeMillis() - start;
            String provider = settings.get(SettingsService.WHISPER_PROVIDER);
            if (result.success()) {
                return new Dtos.ConnectionTestResult(true,
                        "Spracherkennung erreichbar (Anbieter \"" + provider + "\", " + duration + " ms)", duration);
            }
            return new Dtos.ConnectionTestResult(false, result.error(), duration);
        } catch (java.io.IOException e) {
            return new Dtos.ConnectionTestResult(false,
                    "Test-Audio konnte nicht angelegt werden: " + e.getMessage(), 0);
        } finally {
            if (testFile != null) {
                try { java.nio.file.Files.deleteIfExists(testFile); } catch (java.io.IOException ignored) {}
            }
        }
    }

    // ----------------------------------------------------- Authentifizierung

    @GetMapping("/auth")
    public Map<String, String> getAuthConfig() {
        return authSettings.asMap();
    }

    @PutMapping("/auth")
    public Map<String, String> updateAuthConfig(@RequestBody Map<String, String> changes) {
        try {
            authSettings.update(changes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return authSettings.asMap();
    }

    /** Testet die aktuellen LDAP-Einstellungen mit einem echten Bind (Test-Login). */
    @PostMapping("/auth/test")
    public Dtos.LdapTestResult testLdap(@RequestBody Dtos.LdapTestRequest request) {
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test-Benutzername und -Passwort erforderlich");
        }
        try {
            LdapAuthenticator.LdapUser user = ldap.authenticate(request.username().trim(), request.password());
            String msg = "Anmeldung erfolgreich als " + user.username();
            return new Dtos.LdapTestResult(true, msg, user.displayName(), user.email());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AuthenticationException e) {
            return new Dtos.LdapTestResult(false,
                    "Anmeldung fehlgeschlagen: " + e.getMessage(), null, null);
        } catch (RuntimeException e) {
            return new Dtos.LdapTestResult(false,
                    "Verbindung/Anmeldung fehlgeschlagen: " + e.getMessage(), null, null);
        }
    }

    @GetMapping("/users")
    public List<Dtos.UserView> listUsers() {
        return userRepo.findAll().stream().map(Dtos.UserView::of).toList();
    }

    @PutMapping("/users/{userId}/admin")
    public Dtos.UserView setAdmin(@PathVariable UUID userId, @RequestBody Dtos.SetAdminRequest request) {
        AppUser actor = CurrentUser.get();
        AppUser target = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nutzer nicht gefunden"));
        if (actor.getId().equals(target.getId()) && !request.admin()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Eigene Admin-Rolle kann nicht entzogen werden");
        }
        target.setAdmin(request.admin());
        userRepo.save(target);
        return Dtos.UserView.of(target);
    }
}
