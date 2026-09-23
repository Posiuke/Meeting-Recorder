package bbbbot.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;

/**
 * Adresse, unter der der Nutzer die Anwendung gerade aufgerufen hat - das
 * serverseitige Gegenstueck zu {@code window.location.origin}, mit dem die
 * Oberflaeche ihre Freigabe-Links baut.
 *
 * <p>Reihenfolge: {@code Origin}-Header (setzt der Browser bei jedem POST/PUT,
 * Seitenskripte koennen ihn nicht faelschen), dann {@code Referer}, zuletzt die
 * Adresse der Anfrage selbst (beruecksichtigt dank
 * {@code server.forward-headers-strategy} auch X-Forwarded-* eines Proxys).
 */
public final class RequestOrigin {

    private RequestOrigin() {}

    /** Origin der laufenden Web-Anfrage; null ausserhalb einer Anfrage (Scheduler, Tests). */
    public static String current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        return of(attrs.getRequest());
    }

    static String of(HttpServletRequest request) {
        String origin = normalize(request.getHeader("Origin"));
        if (origin != null) return origin;
        String referer = normalize(request.getHeader("Referer"));
        if (referer != null) return referer;
        String host = request.getServerName();
        if (host == null || host.isBlank()) return null;
        int port = request.getServerPort();
        boolean defaultPort = port <= 0
                || ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        return request.getScheme() + "://" + host + (defaultPort ? "" : ":" + port);
    }

    /** Nur Schema, Host und Port einer http(s)-Adresse; alles andere ergibt null. */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw.trim())) return null;
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            return scheme.toLowerCase() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
