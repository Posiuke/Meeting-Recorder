package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.AppUserRepo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
public class UserController {

    /**
     * Sprachen, fuer die es Uebersetzungen im Frontend gibt. Erweitern heisst:
     * hier eintragen UND ein Woerterbuch unter frontend/src/i18n anlegen.
     */
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("de", "en");

    private final AppUserRepo userRepo;

    public UserController(AppUserRepo userRepo) {
        this.userRepo = userRepo;
    }

    /**
     * Oberflaechensprache des angemeldeten Nutzers setzen. Sie wird am Konto
     * gespeichert und gilt damit auf jedem Geraet und in jedem Browser.
     */
    @PutMapping("/me/language")
    public Dtos.UserView setLanguage(@RequestBody Dtos.LanguageRequest request) {
        AppUser user = CurrentUser.get();
        String language = request.language() == null ? "" : request.language().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Sprache nicht unterstuetzt (moeglich: "
                            + String.join(", ", SUPPORTED_LANGUAGES.stream().sorted().toList()) + ")");
        }
        AppUser fresh = userRepo.findById(user.getId()).orElse(user);
        fresh.setLanguage(language);
        userRepo.save(fresh);
        return Dtos.UserView.of(fresh);
    }

    /**
     * Nutzersuche fuer Teilen-/Einladen-Dialoge. Findet nur Nutzer, die sich
     * schon einmal angemeldet haben (das AD wird nicht durchsucht).
     */
    @GetMapping("/search")
    public List<Dtos.UserView> search(@RequestParam String q) {
        if (q == null || q.trim().length() < 2) return List.of();
        String query = q.trim();
        return userRepo.findTop20ByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(query, query)
                .stream()
                .map(Dtos.UserView::of)
                .toList();
    }
}
