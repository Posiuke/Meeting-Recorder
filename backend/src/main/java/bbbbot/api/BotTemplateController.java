package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.domain.BotTemplate;
import bbbbot.bot.BotTemplateLauncher;
import bbbbot.domain.SummaryChoice;
import bbbbot.repository.Repositories.BotTemplateRepo;
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

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
 *
 * <p>Dazu kommen ein optionaler Zeitplan (den der {@link bbbbot.bot.BotScheduler}
 * ausfuehrt) und die Auswertungs-Vorlage fuer die Aufnahmen des Bots.
 */
@RestController
@RequestMapping("/api/bot-templates")
public class BotTemplateController {

    private static final int MAX_TEMPLATES_PER_USER = 100;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_PRESET_LENGTH = 64;

    private final BotTemplateRepo templateRepo;
    private final SettingsService settings;
    private final PromptTemplateRepo promptTemplateRepo;

    public BotTemplateController(BotTemplateRepo templateRepo, SettingsService settings,
                                 PromptTemplateRepo promptTemplateRepo) {
        this.templateRepo = templateRepo;
        this.settings = settings;
        this.promptTemplateRepo = promptTemplateRepo;
    }

    @GetMapping
    public List<Dtos.BotTemplateView> list() {
        AppUser user = CurrentUser.get();
        return templateRepo.findByOwnerIdOrderByNameAsc(user.getId()).stream()
                .map(t -> Dtos.BotTemplateView.of(t, Instant.now()))
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
        rememberOrigin(template);
        saveHandlingDuplicate(template);
        return Dtos.BotTemplateView.of(template, Instant.now());
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
        rememberOrigin(template);
        template.setUpdatedAt(Instant.now());
        saveHandlingDuplicate(template);
        return Dtos.BotTemplateView.of(template, Instant.now());
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
        applySchedule(template, request.schedule());
        applySummary(template, request);
    }

    /**
     * Merkt sich, unter welcher Adresse der Nutzer die Anwendung aufgerufen hat -
     * fuer den Stopp-Link eines spaeter vom Zeitplan gestarteten Bots. Ohne
     * erkennbare Adresse (z.B. Aufruf ohne Browser) bleibt die bisherige stehen.
     */
    private void rememberOrigin(BotTemplate template) {
        String origin = RequestOrigin.current();
        if (origin != null) template.setAppOrigin(origin);
    }

    /**
     * Zeitplan uebernehmen. Auch ein ausgeschalteter Zeitplan behaelt seine
     * Tage und Zeiten - wer ihn fuer die Ferien abschaltet, soll ihn danach
     * nicht neu eintragen muessen. Pflichtangaben gelten deshalb nur, wenn er an
     * ist.
     */
    private void applySchedule(BotTemplate template, Dtos.BotScheduleRequest schedule) {
        if (schedule == null) {
            template.setScheduleEnabled(false);
            return;
        }
        boolean enabled = schedule.enabled() != null && schedule.enabled();
        Set<DayOfWeek> days = schedule.days() == null || schedule.days().isEmpty()
                ? EnumSet.noneOf(DayOfWeek.class)
                : EnumSet.copyOf(schedule.days().stream().filter(d -> d != null).toList());
        if (enabled) {
            if (days.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Zeitplan: mindestens einen Wochentag waehlen");
            }
            if (schedule.start() == null || schedule.end() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Zeitplan: Start- und Endzeit angeben");
            }
            if (schedule.start().equals(schedule.end())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Zeitplan: Start- und Endzeit duerfen nicht gleich sein");
            }
        }
        template.setScheduleEnabled(enabled);
        template.setScheduleDays(days);
        template.setScheduleStart(schedule.start() == null ? null : schedule.start().withSecond(0).withNano(0));
        template.setScheduleEnd(schedule.end() == null ? null : schedule.end().withSecond(0).withNano(0));
        template.setScheduleTimeZone(requireTimeZone(schedule.timeZone()));
    }

    /** IANA-Zeitzone; leer bedeutet die Zeitzone des Servers. */
    private static String requireTimeZone(String raw) {
        if (raw == null || raw.isBlank()) return ZoneId.systemDefault().getId();
        try {
            return ZoneId.of(raw.trim()).getId();
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Zeitplan: unbekannte Zeitzone '" + raw.trim() + "'");
        }
    }

    /**
     * Auswertungs-Vorlage uebernehmen: die Auswahl der Oberflaeche und den
     * aufgeloesten Prompt samt Name, Modell und Temperatur - mit denselben
     * Pruefungen wie beim Upload. Eine gewaehlte eigene Promptvorlage muss dem
     * Nutzer gehoeren.
     */
    private void applySummary(BotTemplate template, Dtos.BotTemplateRequest request) {
        String preset = request.summaryPreset() == null || request.summaryPreset().isBlank()
                ? null : request.summaryPreset().trim();
        if (preset == null) {
            template.setSummaryPreset(null);
            template.setSummaryChoice(SummaryChoice.DEFAULT);
            return;
        }
        if (preset.length() > MAX_PRESET_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Auswertungs-Vorlage ist ungueltig");
        }
        // Eine unveraenderte Auswahl wird nicht erneut geprueft: Ist die eigene
        // Promptvorlage inzwischen geloescht, soll die Bot-Vorlage trotzdem
        // speicherbar bleiben - sie arbeitet dann mit dem gespeicherten Stand.
        boolean unchanged = preset.equals(template.getSummaryPreset());
        if (!unchanged && preset.startsWith(BotTemplateLauncher.OWN_TEMPLATE_PREFIX)) {
            requireOwnPromptTemplate(preset.substring(BotTemplateLauncher.OWN_TEMPLATE_PREFIX.length()),
                    template.getOwnerId());
        }
        template.setSummaryPreset(preset);
        template.setSummaryChoice(new SummaryChoice(
                RecordingController.requireSummaryPrompt(request.summaryPrompt()),
                RecordingController.checkTemplateName(request.summaryTemplateName()),
                PromptTemplateController.checkModel(request.summaryModel()),
                PromptTemplateController.checkTemperature(request.summaryTemperature())));
    }

    private void requireOwnPromptTemplate(String rawId, UUID ownerId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Auswertungs-Vorlage ist ungueltig");
        }
        boolean own = promptTemplateRepo.findById(id)
                .filter(p -> p.getOwnerId().equals(ownerId))
                .isPresent();
        if (!own) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Auswertungs-Vorlage nicht gefunden");
        }
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
