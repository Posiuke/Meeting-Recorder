package bbbbot.api;

import bbbbot.bot.BotInstance;
import bbbbot.bot.BotManager;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Oeffentlicher Stopp-Link: nur mit eingeschalteter Funktion, nur POST beendet,
 * und jeder ungueltige Fall sieht von aussen gleich aus.
 */
class PublicBotStopControllerTest {

    private BotManager botManager;
    private SettingsService settings;
    private PublicBotStopController controller;
    private BotInstance bot;

    @BeforeEach
    void setup() {
        botManager = mock(BotManager.class);
        settings = mock(SettingsService.class);
        controller = new PublicBotStopController(botManager, settings);
        bot = mock(BotInstance.class);
        when(bot.getRoomName()).thenReturn("Technikrunde");
        when(settings.getBool(SettingsService.BOT_ANONYMOUS_STOP_ENABLED)).thenReturn(true);
    }

    @Test
    void anzeigenBeendetNichts() {
        when(botManager.findByStopToken("tok")).thenReturn(Optional.of(bot));

        assertThat(controller.show("tok").roomName()).isEqualTo("Technikrunde");
        verify(botManager, never()).anonymousStop(anyString());
    }

    @Test
    void beendenLoestDenLinkEin() {
        when(botManager.anonymousStop("tok")).thenReturn(Optional.of(bot));

        assertThat(controller.stop("tok").roomName()).isEqualTo("Technikrunde");
    }

    @Test
    void verbrauchterOderUnbekannterLinkErgibt404() {
        when(botManager.findByStopToken(anyString())).thenReturn(Optional.empty());
        when(botManager.anonymousStop(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.show("tok")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> controller.stop("tok")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    /** Admin schaltet ab: auch bereits verschickte Links wirken sofort nicht mehr. */
    @Test
    void ausgeschalteteFunktionSperrtAuchGueltigeLinks() {
        when(settings.getBool(SettingsService.BOT_ANONYMOUS_STOP_ENABLED)).thenReturn(false);
        when(botManager.anonymousStop("tok")).thenReturn(Optional.of(bot));

        assertThatThrownBy(() -> controller.stop("tok")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(botManager, never()).anonymousStop(anyString());
    }
}
