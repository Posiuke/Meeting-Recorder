package bbbbot.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Schaltet die BigBlueButton-Anbindung serverseitig ab, solange
 * {@code BBB_ON} nicht gesetzt ist: Bot-, Bot-Vorlagen- und Stopp-Link-API
 * antworten dann mit 404, als gaebe es sie nicht. Das Frontend blendet die
 * zugehoerigen Reiter anhand von {@code GET /api/public/features} aus - der
 * Riegel hier sorgt dafuer, dass auch die API (z.B. per API-Schluessel) keine
 * Bots starten kann.
 */
@Configuration
public class BbbFeatureConfig implements WebMvcConfigurer {

    static final String[] BBB_PATHS = {
            "/api/bots", "/api/bots/**",
            "/api/bot-templates", "/api/bot-templates/**",
            "/api/public/bot-stop/**",
    };

    private final AppProperties props;

    public BbbFeatureConfig(AppProperties props) {
        this.props = props;
        // Das schlanke Image hat keinen Browser - Bots wuerden erst beim Start scheitern.
        if (props.getBbb().isEnabled() && "slim".equalsIgnoreCase(System.getenv("IMAGE_VARIANT"))) {
            org.slf4j.LoggerFactory.getLogger(BbbFeatureConfig.class).error(
                    "BBB_ON ist gesetzt, aber dies ist das schlanke Image ohne Chromium - "
                            + "Bots koennen nicht starten. Das volle Image verwenden.");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                if (props.getBbb().isEnabled()) {
                    return true;
                }
                response.sendError(HttpStatus.NOT_FOUND.value(), "BigBlueButton-Anbindung ist deaktiviert (BBB_ON)");
                return false;
            }
        }).addPathPatterns(BBB_PATHS);
    }
}
