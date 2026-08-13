package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.domain.PromptTemplate;
import bbbbot.repository.Repositories.PromptTemplateRepo;
import bbbbot.settings.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persoenliche Promptvorlagen: Jeder Nutzer verwaltet seine eigenen benannten
 * Auswertungs-Prompts und kann sie im Dialog "Auswertung anpassen" abrufen.
 */
@RestController
@RequestMapping("/api/prompt-templates")
public class PromptTemplateController {

    private static final int MAX_TEMPLATES_PER_USER = 100;
    private static final int MAX_NAME_LENGTH = 100;
    /** Gleiche Grenze wie fuer den Auswertungs-Prompt pro Aufnahme. */
    private static final int MAX_PROMPT_LENGTH = 8000;

    private final PromptTemplateRepo templateRepo;
    private final SettingsService settings;

    public PromptTemplateController(PromptTemplateRepo templateRepo, SettingsService settings) {
        this.templateRepo = templateRepo;
        this.settings = settings;
    }

    @GetMapping
    public List<Dtos.PromptTemplateView> list() {
        AppUser user = CurrentUser.get();
        return templateRepo.findByOwnerIdOrderByNameAsc(user.getId()).stream()
                .map(Dtos.PromptTemplateView::of)
                .toList();
    }

    /**
     * Standardvorgabe des Administrators, damit sich eine eigene Vorlage aus ihr
     * heraus entwickeln laesst statt bei einem leeren Feld zu beginnen.
     */
    @GetMapping("/default-prompt")
    public Dtos.DefaultPromptView defaultPrompt() {
        return new Dtos.DefaultPromptView(settings.get(SettingsService.SUMMARY_SYSTEM_PROMPT));
    }

    @PostMapping
    public Dtos.PromptTemplateView create(@RequestBody Dtos.PromptTemplateRequest request) {
        AppUser user = CurrentUser.get();
        String name = requireName(request.name());
        String prompt = requirePrompt(request.prompt());
        if (templateRepo.countByOwnerId(user.getId()) >= MAX_TEMPLATES_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximal " + MAX_TEMPLATES_PER_USER + " Vorlagen pro Nutzer");
        }
        if (templateRepo.existsByOwnerIdAndNameIgnoreCase(user.getId(), name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Eine Vorlage mit diesem Namen existiert bereits");
        }
        PromptTemplate template = PromptTemplate.create(user.getId(), name, prompt);
        saveHandlingDuplicate(template);
        return Dtos.PromptTemplateView.of(template);
    }

    @PutMapping("/{id}")
    public Dtos.PromptTemplateView update(@PathVariable UUID id,
                                          @RequestBody Dtos.PromptTemplateRequest request) {
        AppUser user = CurrentUser.get();
        PromptTemplate template = requireOwn(id, user);
        String name = requireName(request.name());
        String prompt = requirePrompt(request.prompt());
        boolean nameChanged = !template.getName().equalsIgnoreCase(name);
        if (nameChanged && templateRepo.existsByOwnerIdAndNameIgnoreCase(user.getId(), name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Eine Vorlage mit diesem Namen existiert bereits");
        }
        template.setName(name);
        template.setPrompt(prompt);
        template.setUpdatedAt(Instant.now());
        saveHandlingDuplicate(template);
        return Dtos.PromptTemplateView.of(template);
    }

    /**
     * Der Unique-Index (owner_id, lower(name)) faengt das Rennen zwischen
     * exists-Pruefung und Insert ab - der Konflikt wird als 409 gemeldet
     * statt als 500 durchzuschlagen.
     */
    private void saveHandlingDuplicate(PromptTemplate template) {
        try {
            templateRepo.save(template);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Eine Vorlage mit diesem Namen existiert bereits");
        }
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        templateRepo.delete(requireOwn(id, user));
    }

    private PromptTemplate requireOwn(UUID id, AppUser user) {
        return templateRepo.findById(id)
                .filter(t -> t.getOwnerId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vorlage nicht gefunden"));
    }

    private String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name darf nicht leer sein");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Name ist zu lang (max. " + MAX_NAME_LENGTH + " Zeichen)");
        }
        return name;
    }

    private String requirePrompt(String raw) {
        String prompt = raw == null ? "" : raw.trim();
        if (prompt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt darf nicht leer sein");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Prompt ist zu lang (max. " + MAX_PROMPT_LENGTH + " Zeichen)");
        }
        return prompt;
    }
}
