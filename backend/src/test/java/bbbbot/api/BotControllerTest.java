package bbbbot.api;

import bbbbot.bot.BotInstance;
import bbbbot.bot.BotManager;
import bbbbot.domain.AppUser;
import bbbbot.domain.BotSession;
import bbbbot.repository.Repositories.BotSessionRepo;
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

    private AppUser owner;
    private AppUser other;
    private AppUser admin;

    @BeforeEach
    void setup() {
        botManager = mock(BotManager.class);
        sessionRepo = mock(BotSessionRepo.class);
        settings = mock(SettingsService.class);
        controller = new BotController(botManager, sessionRepo, settings);

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
}
