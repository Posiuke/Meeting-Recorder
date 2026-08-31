package bbbbot.api;

import bbbbot.domain.AppUser;
import bbbbot.domain.BotTemplate;
import bbbbot.repository.Repositories.BotTemplateRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bot-Vorlagen, wie der Bereich "Bots" sie benutzt: Vorgaben der Schalter, die
 * gemeinsamen Regeln fuer Meeting-URL und Bot-Namen und die Trennung zwischen
 * den Vorlagen verschiedener Nutzer.
 */
class BotTemplateControllerTest {

    private BotTemplateRepo templateRepo;
    private SettingsService settings;
    private BotTemplateController controller;

    private AppUser user;

    @BeforeEach
    void setup() {
        templateRepo = mock(BotTemplateRepo.class);
        settings = mock(SettingsService.class);
        // Keine Allowlist konfiguriert = jeder Host erlaubt (Standard).
        when(settings.get(SettingsService.BOT_ALLOWED_URL_HOSTS)).thenReturn("");
        controller = new BotTemplateController(templateRepo, settings);

        user = AppUser.create("m.mustermann", "Mustermann", "m@example.org");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void freierName() {
        when(templateRepo.countByOwnerId(user.getId())).thenReturn(0L);
        when(templateRepo.existsByOwnerIdAndNameIgnoreCase(eq(user.getId()), anyString()))
                .thenReturn(false);
    }

    private Dtos.BotTemplateRequest request(String name, String url) {
        return new Dtos.BotTemplateRequest(name, url, null, null, null, null, null, null);
    }

    @Test
    void legtVorlageMitDenVorgabenDesFormularsAn() {
        freierName();

        var view = controller.create(request("  Technikrunde  ", "  https://bbb.example.org/b/abc  "));

        assertThat(view.name()).isEqualTo("Technikrunde");
        assertThat(view.meetingUrl()).isEqualTo("https://bbb.example.org/b/abc");
        // Leerer Bot-Name = Standardname; automatisch aufnehmen und KI-Analyse an,
        // Video und Sprechererkennung aus - wie im Bot-Formular.
        assertThat(view.botName()).isEqualTo(BotController.DEFAULT_BOT_NAME);
        assertThat(view.autoRecord()).isTrue();
        assertThat(view.aiAnalysis()).isTrue();
        assertThat(view.recordVideo()).isFalse();
        assertThat(view.diarize()).isFalse();
        assertThat(view.sttLanguage()).isNull();
        verify(templateRepo).save(any(BotTemplate.class));
    }

    @Test
    void uebernimmtEinstellungenUndSprache() {
        freierName();

        var view = controller.create(new Dtos.BotTemplateRequest("Vorstand",
                "https://bbb.example.org/b/xyz", " Protokoll-Bot ",
                false, true, true, true, "EN"));

        assertThat(view.botName()).isEqualTo("Protokoll-Bot");
        assertThat(view.autoRecord()).isFalse();
        assertThat(view.recordVideo()).isTrue();
        assertThat(view.diarize()).isTrue();
        assertThat(view.sttLanguage()).isEqualTo("en");
    }

    /**
     * Die Vorlage haelt den Wunsch fest, nicht die Freigabe: Ist die
     * Sprechererkennung gesperrt, bleibt sie in der Vorlage trotzdem stehen -
     * ueber die Ausfuehrung entscheidet der Start.
     */
    @Test
    void merktSichSprechererkennungAuchOhneFreigabe() {
        freierName();
        when(settings.getBool(SettingsService.WHISPER_DIARIZE)).thenReturn(false);

        var view = controller.create(new Dtos.BotTemplateRequest("Vorstand",
                "https://bbb.example.org/b/xyz", null, null, null, null, true, null));

        assertThat(view.diarize()).isTrue();
    }

    @Test
    void weistLeerenNamenUndFehlendeUrlAb() {
        assertThatThrownBy(() -> controller.create(request("   ", "https://bbb.example.org/b/abc")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Name darf nicht leer sein");

        assertThatThrownBy(() -> controller.create(request("Technikrunde", "  ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Meeting-URL erforderlich");

        assertThatThrownBy(() -> controller.create(request("Technikrunde", "bbb.example.org/b/abc")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("http(s)://");

        verify(templateRepo, never()).save(any());
    }

    /**
     * Dieselbe Allowlist wie beim Sofort-Start: Eine Vorlage, die beim Starten
     * an der SSRF-Sperre scheitern wuerde, laesst sich nicht speichern.
     */
    @Test
    void haeltSichAnDieAllowlistDerHosts() {
        when(settings.get(SettingsService.BOT_ALLOWED_URL_HOSTS)).thenReturn("bbb.intern.dom");
        freierName();

        assertThatThrownBy(() -> controller.create(request("Fremd", "https://evil.example.com/b/abc")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nicht in der erlaubten Liste");

        var view = controller.create(request("Intern", "https://html5.bbb.intern.dom/b/abc"));
        assertThat(view.meetingUrl()).isEqualTo("https://html5.bbb.intern.dom/b/abc");
    }

    @Test
    void weistDoppelteNamenAb() {
        when(templateRepo.countByOwnerId(user.getId())).thenReturn(3L);
        when(templateRepo.existsByOwnerIdAndNameIgnoreCase(user.getId(), "Technikrunde"))
                .thenReturn(true);

        assertThatThrownBy(() -> controller.create(request("Technikrunde", "https://bbb.example.org/b/abc")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("existiert bereits");
    }

    @Test
    void aendertDieEigeneVorlageUndSetztDenAenderungszeitpunkt() {
        BotTemplate own = BotTemplate.create(user.getId(), "Alt",
                "https://bbb.example.org/b/alt", "RecorderBot");
        when(templateRepo.findById(own.getId())).thenReturn(Optional.of(own));

        var view = controller.update(own.getId(), new Dtos.BotTemplateRequest("Neu",
                "https://bbb.example.org/b/neu", "Neuer Bot", true, true, true, false, "de"));

        assertThat(view.name()).isEqualTo("Neu");
        assertThat(view.meetingUrl()).isEqualTo("https://bbb.example.org/b/neu");
        assertThat(view.recordVideo()).isTrue();
        assertThat(view.updatedAt()).isNotNull();
    }

    @Test
    void greiftNichtAufFremdeVorlagenZu() {
        BotTemplate foreign = BotTemplate.create(UUID.randomUUID(), "Fremd",
                "https://bbb.example.org/b/fremd", "RecorderBot");
        when(templateRepo.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> controller.update(foreign.getId(),
                request("Neu", "https://bbb.example.org/b/neu")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nicht gefunden");

        assertThatThrownBy(() -> controller.delete(foreign.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nicht gefunden");

        verify(templateRepo, never()).delete(any());
    }

    /** Der Bot-Name landet in einer Spalte mit Laengengrenze - 400 statt 500. */
    @Test
    void weistZuLangenBotNamenAb() {
        freierName();

        assertThatThrownBy(() -> controller.create(new Dtos.BotTemplateRequest("Lang",
                "https://bbb.example.org/b/abc", "B".repeat(101),
                null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Bot-Name ist zu lang");
    }
}
