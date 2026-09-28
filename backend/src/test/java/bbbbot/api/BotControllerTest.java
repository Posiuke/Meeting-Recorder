package bbbbot.api;

import bbbbot.bot.BotInstance;
import bbbbot.bot.BotManager;
import bbbbot.bot.BotTemplateLauncher;
import bbbbot.domain.AppUser;
import bbbbot.domain.BotSession;
import bbbbot.repository.Repositories.BotSessionRepo;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.BotTemplateRepo;
import bbbbot.sharing.BotTemplateAccess;
import bbbbot.domain.BotTemplate;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotControllerTest {

    private BotManager botManager;
    private BotSessionRepo sessionRepo;
    private SettingsService settings;
    private BotController controller;
    private BotTemplateRepo templateRepo;
    private BotTemplateLauncher launcher;
    private BotTemplateAccess templateAccess;

    private AppUser owner;
    private AppUser other;
    private AppUser admin;

    @BeforeEach
    void setup() {
        botManager = mock(BotManager.class);
        sessionRepo = mock(BotSessionRepo.class);
        settings = mock(SettingsService.class);
        templateRepo = mock(BotTemplateRepo.class);
        launcher = mock(BotTemplateLauncher.class);
        templateAccess = mock(BotTemplateAccess.class);
        controller = new BotController(botManager, sessionRepo, settings,
                templateRepo, launcher, templateAccess, mock(AppUserRepo.class));

        owner = AppUser.create("owner", "Owner", "o@x");
        other = AppUser.create("other", "Other", "e@x");
        admin = AppUser.create("admin", "Admin", "a@x");
        admin.setAdmin(true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(AppUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }

    private UUID activeSessionOwnedBy(AppUser user) {
        UUID sessionId = UUID.randomUUID();
        BotInstance instance = mock(BotInstance.class);
        when(instance.getOwnerId()).thenReturn(user.getId());
        when(botManager.get(sessionId)).thenReturn(Optional.of(instance));
        return sessionId;
    }

    // ------------------------------------------------------- Owner-Check

    @Test
    void besitzerDarfBotStoppen() {
        UUID sessionId = activeSessionOwnedBy(owner);
        login(owner);

        controller.stop(sessionId);

        verify(botManager).stopBot(sessionId);
    }

    @Test
    void fremderDarfFremdenBotNichtStoppen() {
        UUID sessionId = activeSessionOwnedBy(owner);
        login(other);

        assertThatThrownBy(() -> controller.stop(sessionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verify(botManager, never()).stopBot(any());
    }

    @Test
    void adminDarfFremdenBotStoppen() {
        UUID sessionId = activeSessionOwnedBy(owner);
        login(admin);

        controller.stop(sessionId);

        verify(botManager).stopBot(sessionId);
    }

    @Test
    void unbekannteSessionErgibt404() {
        UUID sessionId = UUID.randomUUID();
        when(botManager.get(sessionId)).thenReturn(Optional.empty());
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.empty());
        login(owner);

        assertThatThrownBy(() -> controller.stop(sessionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void verwaisteSessionNutztCreatedByFuerOwnerCheck() {
        UUID sessionId = UUID.randomUUID();
        BotSession session = BotSession.create("https://x/y", "Bot", owner.getId(), true, false, true, false);
        when(botManager.get(sessionId)).thenReturn(Optional.empty());
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        login(other);

        assertThatThrownBy(() -> controller.stop(sessionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    // ------------------------------------------------------- SSRF-Allowlist

    @Test
    void hostMatchesLeereListeErlaubtAlles() {
        assertThat(BotController.hostMatches("evil.example.com", "")).isTrue();
        assertThat(BotController.hostMatches("evil.example.com", null)).isTrue();
    }

    @Test
    void hostMatchesExaktUndSubdomain() {
        assertThat(BotController.hostMatches("bbb.intern.dom", "bbb.intern.dom")).isTrue();
        assertThat(BotController.hostMatches("html5.bbb.intern.dom", "bbb.intern.dom")).isTrue();
        assertThat(BotController.hostMatches("BBB.Intern.Dom", "bbb.intern.dom")).isTrue();
    }

    @Test
    void hostMatchesLehntFremdeHostsAb() {
        assertThat(BotController.hostMatches("evil.com", "bbb.intern.dom")).isFalse();
        assertThat(BotController.hostMatches("bbb.intern.dom.evil.com", "bbb.intern.dom")).isFalse();
        assertThat(BotController.hostMatches("169.254.169.254", "bbb.intern.dom")).isFalse();
    }

    @Test
    void hostMatchesMehrereEintraege() {
        String csv = "bbb.intern.dom, nextcloud.example.org";
        assertThat(BotController.hostMatches("nextcloud.example.org", csv)).isTrue();
        assertThat(BotController.hostMatches("x.bbb.intern.dom", csv)).isTrue();
        assertThat(BotController.hostMatches("other.net", csv)).isFalse();
    }

    // ------------------------------------------------------- Verlaengern

    @Test
    void verlaengernPrueftDieMinuten() {
        UUID sessionId = activeSessionOwnedBy(owner);
        login(owner);

        for (Integer minutes : new Integer[] {null, 0, -5, 12 * 60 + 1}) {
            assertThatThrownBy(() -> controller.extendSchedule(sessionId,
                    new Dtos.ExtendBotScheduleRequest(minutes, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400");
        }
        verify(botManager, never()).extendScheduledStop(any(), any(), any());
    }

    @Test
    void fremderDarfFremdenBotNichtVerlaengern() {
        UUID sessionId = activeSessionOwnedBy(owner);
        login(other);

        assertThatThrownBy(() -> controller.extendSchedule(sessionId,
                new Dtos.ExtendBotScheduleRequest(30, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verify(botManager, never()).extendScheduledStop(any(), any(), any());
    }

    @Test
    void botOhneGeplantesEndeErgibt409() {
        UUID sessionId = activeSessionOwnedBy(owner);
        login(owner);
        when(botManager.extendScheduledStop(any(), any(), any()))
                .thenThrow(new IllegalStateException("Dieser Bot hat kein geplantes Ende"));

        assertThatThrownBy(() -> controller.extendSchedule(sessionId,
                new Dtos.ExtendBotScheduleRequest(30, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    // ------------------------------------------------------- Geteilte Vorlagen

    private UUID botAusGeteilterVorlage(UUID templateId) {
        UUID sessionId = UUID.randomUUID();
        BotInstance instance = mock(BotInstance.class);
        when(instance.getSessionId()).thenReturn(sessionId);
        when(instance.getOwnerId()).thenReturn(owner.getId());
        when(instance.getBotTemplateId()).thenReturn(templateId);
        when(instance.getStatus()).thenReturn(BotSession.Status.JOINED);
        when(instance.getMeetingUrl()).thenReturn("https://bbb.example.org/b/jf");
        when(botManager.get(sessionId)).thenReturn(Optional.of(instance));
        when(botManager.listActive()).thenReturn(java.util.List.of(instance));
        return sessionId;
    }

    /** Auch vom Zeitplan gestartete Bots sieht, wer die Vorlage geteilt bekommen hat. */
    @Test
    void empfaengerSiehtUndSteuertBotDerGeteiltenVorlage() {
        UUID templateId = UUID.randomUUID();
        UUID sessionId = botAusGeteilterVorlage(templateId);
        when(templateAccess.sharedTemplateIds(other)).thenReturn(java.util.Set.of(templateId));
        login(other);

        assertThat(controller.listActive()).extracting(Dtos.BotView::sessionId).containsExactly(sessionId);
        assertThat(controller.listActive().get(0).mine()).isFalse();
        controller.stop(sessionId);
        verify(botManager).stopBot(sessionId);
    }

    @Test
    void ohneFreigabeKeinFremderBotInDerListe() {
        botAusGeteilterVorlage(UUID.randomUUID());
        login(other);

        assertThat(controller.listActive()).isEmpty();
    }

    @Test
    void empfaengerStartetBotAusGeteilterVorlage() {
        BotTemplate template = BotTemplate.create(owner.getId(), "Jour fixe",
                "https://bbb.example.org/b/jf", "RecorderBot");
        when(templateRepo.findById(template.getId())).thenReturn(Optional.of(template));
        when(templateAccess.canUse(template, other)).thenReturn(true);
        BotSession session = BotSession.create(template.getMeetingUrl(), "RecorderBot",
                owner.getId(), true, false, true, false);
        when(launcher.startNow(any(), any(), any())).thenReturn(session);
        UUID sessionId = botAusGeteilterVorlage(template.getId());
        Optional<BotInstance> running = botManager.get(sessionId);
        when(botManager.get(session.getId())).thenReturn(running);
        login(other);

        controller.startFromTemplate(template.getId());

        verify(launcher).startNow(any(), any(), any());
    }

    @Test
    void ohneFreigabeKeinStartAusFremderVorlage() {
        BotTemplate template = BotTemplate.create(owner.getId(), "Jour fixe",
                "https://bbb.example.org/b/jf", "RecorderBot");
        when(templateRepo.findById(template.getId())).thenReturn(Optional.of(template));
        login(other);

        assertThatThrownBy(() -> controller.startFromTemplate(template.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
