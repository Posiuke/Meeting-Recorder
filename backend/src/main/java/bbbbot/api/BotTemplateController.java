package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.domain.BotTemplate;
import bbbbot.repository.Repositories.BotTemplateRepo;
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
 * Persoenliche Bot-Vorlagen (Issue #27): Wer denselben Meetingraum regelmaessig
 * aufzeichnet, legt Name, URL und Einstellungen einmal ab; danach bleibt nur
 * noch "Vorlage waehlen, Bot starten".
 *
 * <p>Streng benutzerbezogen wie die Promptvorlagen - in der Meeting-URL steckt
 * der Zugang zum Raum, sie ist deshalb fuer niemand anderen sichtbar. Auch
 * Admins bekommen hier nur ihre eigenen Vorlagen.
 *
 * <p>Gestartet wird ueber {@code POST /api/bots}: Die Vorlage haelt nur die
 * Angaben, den Start verantwortet weiterhin der {@link BotController}. Deshalb
 * pruefen beide dieselben Regeln fuer URL und Bot-Namen - eine Vorlage, die
 * beim Starten scheitern wuerde, laesst sich gar nicht erst speichern.
 */
@RestController
@RequestMapping("/api/bot-templates")
public class BotTemplateController {

    private static final int MAX_TEMPLATES_PER_USER = 100;
    private static final int MAX_NAME_LENGTH = 100;

    private final BotTemplateRepo templateRepo;
    private final SettingsService settings;

    public BotTemplateController(BotTemplateRepo templateRepo, SettingsService settings) {
        this.templateRepo = templateRepo;
        this.settings = settings;
    }

    @GetMapping
    public List<Dtos.BotTemplateView> list() {
        AppUser user = CurrentUser.get();
        return templateRepo.findByOwnerIdOrderByNameAsc(user.getId()).stream()
                .map(Dtos.BotTemplateView::of)
                .toList();
    }

    @PostMapping
    public Dtos.BotTemplateView create(@RequestBody Dtos.BotTemplateRequest request) {
        AppUser user = CurrentUser.get();
        String name = requireName(request.name());
        if (templateRepo.countByOwnerId(user.getId()) >= MAX_TEMPLATES_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximal " + MAX_TEMPLATES_PER_USER + " Bot-Vorlagen pro Nutzer");
        }
        if (templateRepo.existsByOwnerIdAndNameIgnoreCase(user.getId(), name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Eine Bot-Vorlage mit diesem Namen existiert bereits");
        }
        BotTemplate template = BotTemplate.create(user.getId(), name,
                BotController.requireMeetingUrl(request.meetingUrl(), settings),
                BotController.requireBotName(request.botName()));
        applySettings(template, request);
        saveHandlingDuplicate(template);
        return Dtos.BotTemplateView.of(template);
    }

    @PutMapping("/{id}")
    public Dtos.BotTemplateView update(@PathVariable UUID id,
                                       @RequestBody Dtos.BotTemplateRequest request) {
        AppUser user = CurrentUser.get();
        BotTemplate template = requireOwn(id, user);
        String name = requireName(request.name());
        boolean nameChanged = !template.getName().equalsIgnoreCase(name);
        if (nameChanged && templateRepo.existsByOwnerIdAndNameIgnoreCase(user.getId(), name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Eine Bot-Vorlage mit diesem Namen existiert bereits");
        }
        template.setName(name);
        template.setMeetingUrl(BotController.requireMeetingUrl(request.meetingUrl(), settings));
        template.setBotName(BotController.requireBotName(request.botName()));
        applySettings(template, request);
        template.setUpdatedAt(Instant.now());
        saveHandlingDuplicate(template);
        return Dtos.BotTemplateView.of(template);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        templateRepo.delete(requireOwn(id, user));
    }

    /**
     * Die Schalter der Aufnahme, mit denselben Vorgaben wie im Bot-Formular:
     * Automatisch aufnehmen und KI-Analyse sind an, wenn nichts angegeben ist,
     * Video und Sprechererkennung aus.
     *
     * <p>Die Sprechererkennung wird hier NICHT gegen {@code whisper.diarize}
     * geprueft: Die Vorlage haelt den Wunsch des Nutzers fest, ueber die
     * Ausfuehrung entscheidet der Start. Sonst verliert eine Vorlage die
     * Einstellung, nur weil sie waehrend einer Sperre gespeichert wurde.
     */
    private void applySettings(BotTemplate template, Dtos.BotTemplateRequest request) {
        template.setAutoRecord(request.autoRecord() == null || request.autoRecord());
        template.setRecordVideo(request.recordVideo() != null && request.recordVideo());
        template.setAiAnalysis(request.aiAnalysis() == null || request.aiAnalysis());
        template.setDiarize(request.diarize() != null && request.diarize());
        template.setSttLanguage(RecordingController.requireSttLanguage(request.sttLanguage()));
    }

    /**
     * Der Unique-Index (owner_id, lower(name)) faengt das Rennen zwischen
     * exists-Pruefung und Insert ab - der Konflikt wird als 409 gemeldet statt
     * als 500 durchzuschlagen.
     */
    private void saveHandlingDuplicate(BotTemplate template) {
        try {
            templateRepo.save(template);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Eine Bot-Vorlage mit diesem Namen existiert bereits");
        }
    }

    private BotTemplate requireOwn(UUID id, AppUser user) {
        return templateRepo.findById(id)
                .filter(t -> t.getOwnerId().equals(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Bot-Vorlage nicht gefunden"));
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
}
