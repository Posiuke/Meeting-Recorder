package bbbbot.api;

import bbbbot.domain.AppUser;
import bbbbot.domain.PromptTemplate;
import bbbbot.repository.Repositories.PromptTemplateRepo;
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
 * Die Vorlagenverwaltung, wie der Tab "Vorlagen" sie benutzt: Standardvorgabe
 * als Ausgangspunkt, Grenzen beim Anlegen und die Trennung zwischen den
 * Vorlagen verschiedener Nutzer.
 */
class PromptTemplateControllerTest {

    private PromptTemplateRepo templateRepo;
    private SettingsService settings;
    private PromptTemplateController controller;

    private AppUser user;

    @BeforeEach
    void setup() {
        templateRepo = mock(PromptTemplateRepo.class);
        settings = mock(SettingsService.class);
        controller = new PromptTemplateController(templateRepo, settings);

        user = AppUser.create("m.mustermann", "Mustermann", "m@example.org");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void liefertDieStandardvorgabeAlsAusgangspunkt() {
        when(settings.get(SettingsService.SUMMARY_SYSTEM_PROMPT)).thenReturn("Fasse zusammen.");

        assertThat(controller.defaultPrompt().prompt()).isEqualTo("Fasse zusammen.");
    }

    @Test
    void legtVorlageAnUndSchneidetLeerzeichenAb() {
        when(templateRepo.countByOwnerId(user.getId())).thenReturn(0L);
        when(templateRepo.existsByOwnerIdAndNameIgnoreCase(eq(user.getId()), anyString()))
                .thenReturn(false);

        var view = controller.create(new Dtos.PromptTemplateRequest("  Nur Aufgaben  ", " Liste Aufgaben. "));

        assertThat(view.name()).isEqualTo("Nur Aufgaben");
        assertThat(view.prompt()).isEqualTo("Liste Aufgaben.");
        verify(templateRepo).save(any(PromptTemplate.class));
    }

    @Test
    void weistLeerenNamenUndLeerenPromptAb() {
        assertThatThrownBy(() -> controller.create(new Dtos.PromptTemplateRequest("   ", "Text")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Name darf nicht leer sein");

        assertThatThrownBy(() -> controller.create(new Dtos.PromptTemplateRequest("Name", "  ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Prompt darf nicht leer sein");

        verify(templateRepo, never()).save(any());
    }

    @Test
    void weistDoppelteNamenAb() {
        when(templateRepo.countByOwnerId(user.getId())).thenReturn(3L);
        when(templateRepo.existsByOwnerIdAndNameIgnoreCase(user.getId(), "Nur Aufgaben"))
                .thenReturn(true);

        assertThatThrownBy(() ->
                controller.create(new Dtos.PromptTemplateRequest("Nur Aufgaben", "Liste Aufgaben.")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("existiert bereits");
    }

    @Test
    void aendertDieEigeneVorlageUndSetztDenAenderungszeitpunkt() {
        PromptTemplate own = PromptTemplate.create(user.getId(), "Alt", "Alter Text");
        when(templateRepo.findById(own.getId())).thenReturn(Optional.of(own));

        var view = controller.update(own.getId(), new Dtos.PromptTemplateRequest("Neu", "Neuer Text"));

        assertThat(view.name()).isEqualTo("Neu");
        assertThat(view.prompt()).isEqualTo("Neuer Text");
        assertThat(view.updatedAt()).isNotNull();
    }

    @Test
    void greiftNichtAufFremdeVorlagenZu() {
        PromptTemplate foreign = PromptTemplate.create(UUID.randomUUID(), "Fremd", "Fremder Text");
        when(templateRepo.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() ->
                controller.update(foreign.getId(), new Dtos.PromptTemplateRequest("Neu", "Neuer Text")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nicht gefunden");

        assertThatThrownBy(() -> controller.delete(foreign.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nicht gefunden");

        verify(templateRepo, never()).delete(any());
    }
}
