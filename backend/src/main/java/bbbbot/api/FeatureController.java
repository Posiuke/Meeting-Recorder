package bbbbot.api;

import bbbbot.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Welche optionalen Bereiche diese Installation anbietet. Oeffentlich, weil
 * schon die Anmelde- und Stopp-Seite davon abhaengen koennen und die Angabe
 * nichts Vertrauliches verraet.
 */
@RestController
public class FeatureController {

    private final AppProperties props;

    public FeatureController(AppProperties props) {
        this.props = props;
    }

    /** {@code bbbEnabled}: BigBlueButton-Bots verfuegbar (Umgebungsvariable BBB_ON). */
    public record FeaturesView(boolean bbbEnabled) {}

    @GetMapping("/api/public/features")
    public FeaturesView features() {
        return new FeaturesView(props.getBbb().isEnabled());
    }
}
