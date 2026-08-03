package bbbbot.auth;

import bbbbot.domain.ApiKey;
import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.AppUserRepo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Anmeldung per API-Schluessel. Damit stehen Skripten dieselben Endpunkte offen,
 * die auch die Weboberflaeche nutzt - es gibt bewusst keine zweite, parallel zu
 * pflegende API-Flaeche.
 *
 * <p>Der Schluessel wird als {@code X-API-Key: bbb_...} oder als
 * {@code Authorization: Bearer bbb_...} mitgegeben; am Prefix ist er von einem
 * Login-Token (JWT) unterscheidbar.
 *
 * <p>Zwei Einschraenkungen gelten unabhaengig von den Rechten des Nutzers:
 * <ul>
 *   <li>Ein Nur-Lese-Schluessel darf ausschliesslich GET/HEAD/OPTIONS.</li>
 *   <li>Schluesselverwaltung und Passwortwechsel gehen nicht per Schluessel.
 *       Sonst koennte ein abgeflossener Schluessel sich selbst verlaengern,
 *       weitere Schluessel anlegen oder den Nutzer aussperren - genau das soll
 *       ein Widerruf in der Weboberflaeche zuverlaessig beenden.</li>
 * </ul>
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    public static final String HEADER = "X-API-Key";

    /** Kennzeichnet einen per Schluessel angemeldeten Aufruf (z.B. fuers Log). */
    public static final String REQUEST_ATTRIBUTE = "bbbbot.apiKeyId";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Pfade, die einem API-Schluessel grundsaetzlich verschlossen bleiben. */
    private static final List<String> FORBIDDEN_PATHS = List.of(
            "/api/api-keys",
            "/api/auth/change-password");

    private final ApiKeyService apiKeyService;
    private final AppUserRepo userRepo;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, AppUserRepo userRepo) {
        this.apiKeyService = apiKeyService;
        this.userRepo = userRepo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = presentedToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiKey> found = apiKeyService.authenticate(token);
        if (found.isEmpty()) {
            // Ein vorgelegter, aber ungueltiger Schluessel wird sofort beantwortet:
            // Ohne diese Meldung wuerde der Aufrufer nur ein nichtssagendes 401
            // sehen und den Fehler beim Endpunkt suchen.
            deny(response, HttpStatus.UNAUTHORIZED,
                    "API-Schluessel ist unbekannt, abgelaufen oder widerrufen");
            return;
        }
        ApiKey key = found.get();
        AppUser user = userRepo.findById(key.getOwnerId()).orElse(null);
        if (user == null) {
            deny(response, HttpStatus.UNAUTHORIZED, "Der Nutzer dieses API-Schluessels existiert nicht mehr");
            return;
        }
        if (isForbiddenPath(request)) {
            deny(response, HttpStatus.FORBIDDEN,
                    "Dieser Bereich ist nur in der Weboberflaeche zugaenglich, nicht per API-Schluessel");
            return;
        }
        if (key.isReadOnly() && !READ_METHODS.contains(request.getMethod())) {
            deny(response, HttpStatus.FORBIDDEN,
                    "Dieser API-Schluessel darf nur lesen (" + request.getMethod() + " nicht erlaubt)");
            return;
        }

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.isAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, authorities));
        request.setAttribute(REQUEST_ATTRIBUTE, key.getId());
        log.debug("API-Aufruf {} {} mit Schluessel {} von {}",
                request.getMethod(), request.getRequestURI(), key.getTokenPrefix(), user.getUsername());
        chain.doFilter(request, response);
    }

    /** Schluessel aus dem eigenen Header oder aus {@code Authorization: Bearer}. */
    private static String presentedToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) return header.strip();
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String value = authorization.substring(7).strip();
            // Nur wenn es wirklich ein Schluessel ist - sonst gehoert das Token
            // dem Login (JWT) und wird vom JwtAuthFilter behandelt.
            if (value.startsWith(ApiKey.TOKEN_PREFIX)) return value;
        }
        return null;
    }

    private static boolean isForbiddenPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        for (String forbidden : FORBIDDEN_PATHS) {
            if (path.equals(forbidden) || path.startsWith(forbidden + "/")) return true;
        }
        return false;
    }

    /** Fehlerantwort im selben Format wie der ApiExceptionHandler ({@code message}). */
    private static void deny(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
