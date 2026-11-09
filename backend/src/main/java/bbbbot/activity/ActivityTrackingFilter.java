package bbbbot.activity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Merkt sich die letzte Nutzeranfrage an die API (angemeldet, per Schluessel
 * oder ueber einen Freigabe-Link). Nicht mit: die Abfrage der Leerlauf-Meldung
 * selbst, Health-Checks und automatisches Nachladen der Oberflaeche (Header
 * {@code X-Background-Request}) - sonst hielte ein offener Tab den Dienst ewig wach.
 */
@Component
public class ActivityTrackingFilter extends OncePerRequestFilter {

    static final String BACKGROUND_HEADER = "X-Background-Request";

    private final ActivityService activity;

    public ActivityTrackingFilter(ActivityService activity) {
        this.activity = activity;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith(ActivityController.PATH)
                || request.getHeader(BACKGROUND_HEADER) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        activity.touch();
        chain.doFilter(request, response);
    }
}
