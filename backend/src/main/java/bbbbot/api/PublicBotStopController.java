package bbbbot.api;

import bbbbot.bot.BotInstance;
import bbbbot.bot.BotManager;
import bbbbot.settings.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Anonymer Stopp-Link fuer Meeting-Teilnehmer, ohne Anmeldung: Wer die
 * Aufnahme nicht will, muss sich nicht per STOPRECORDING im Chat zu erkennen
 * geben. Der Bot haengt den Link an seinen Aufnahme-Hinweis.
 *
 * <p>Datenschutz ist hier der Zweck, deshalb:
 * <ul>
 *   <li>Nur POST loest etwas aus - Link-Vorschauen und Virenscanner rufen per
 *       GET auf und duerfen die Aufnahme nicht versehentlich beenden.</li>
 *   <li>Es wird nichts ueber den Aufrufer gespeichert oder geloggt.</li>
 *   <li>Unbekannt, verbraucht, abgelaufen oder Funktion aus: immer dieselbe
 *       404 - von aussen ist nicht unterscheidbar, ob ein Link je gueltig war.</li>
 * </ul>
 * Die Admin-Einstellung wird bei jedem Aufruf gelesen: Schaltet der Admin die
 * Funktion ab, sind auch bereits verschickte Links sofort wirkungslos.
 */
@RestController
@RequestMapping("/api/public/bot-stop/{token}")
public class PublicBotStopController {

    private final BotManager botManager;
    private final SettingsService settings;

    public PublicBotStopController(BotManager botManager, SettingsService settings) {
        this.botManager = botManager;
        this.settings = settings;
    }

    /** Die Seite zeigt den Raumnamen, damit man sicher das richtige Meeting beendet. */
    public record StopLinkView(String roomName) {}

    @GetMapping
    public StopLinkView show(@PathVariable String token) {
        requireEnabled();
        BotInstance bot = botManager.findByStopToken(token).orElseThrow(PublicBotStopController::invalid);
        return new StopLinkView(bot.getRoomName());
    }

    @PostMapping
    public StopLinkView stop(@PathVariable String token) {
        requireEnabled();
        BotInstance bot = botManager.anonymousStop(token).orElseThrow(PublicBotStopController::invalid);
        return new StopLinkView(bot.getRoomName());
    }

    private void requireEnabled() {
        if (!settings.getBool(SettingsService.BOT_ANONYMOUS_STOP_ENABLED)) throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Dieser Link ist nicht (mehr) gueltig - die Aufnahme ist bereits beendet.");
    }
}
