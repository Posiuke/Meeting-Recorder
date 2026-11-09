package bbbbot.auth.oidc;

import bbbbot.auth.JwtService;
import bbbbot.config.AppProperties;
import bbbbot.domain.AppUser;
import bbbbot.domain.OidcProvider;
import bbbbot.repository.Repositories.OidcProviderRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Ablauf der OIDC-Anmeldung (Authorization Code Flow mit PKCE):
 * <ol>
 *   <li>{@link #start}: state, nonce und PKCE-Verifier erzeugen, signiert in ein
 *       kurzlebiges Cookie legen, Weiterleitung zum Anbieter.</li>
 *   <li>{@link #complete}: state gegen das Cookie pruefen, Code tauschen,
 *       ID-Token pruefen, Konto zuordnen, eigenes JWT ausstellen.</li>
 * </ol>
 * Danach ist die Anmeldung genau dieselbe wie mit Passwort: Die Oberflaeche
 * haelt das eigene JWT, der Anbieter ist nicht mehr beteiligt.
 */
@Service
public class OidcLoginService {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);

    /** Wie lange der Nutzer beim Anbieter fuer die Anmeldung brauchen darf. */
    public static final Duration STATE_VALIDITY = Duration.ofMinutes(10);

    private final OidcProviderRepo providerRepo;
    private final OidcClient client;
    private final OidcUserMapper userMapper;
    private final JwtService jwtService;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();

    public OidcLoginService(OidcProviderRepo providerRepo, OidcClient client, OidcUserMapper userMapper,
                            JwtService jwtService, AppProperties props) {
        this.providerRepo = providerRepo;
        this.client = client;
        this.userMapper = userMapper;
        this.jwtService = jwtService;
        this.props = props;
    }

    /** Weiterleitung zum Anbieter plus der signierte Zwischenstand fuers Cookie. */
    public record Start(String authorizationUrl, String stateCookie) {}

    public record Result(String token, AppUser user, String next) {}

    public Start start(String slug, String next, HttpServletRequest request) throws OidcException {
        OidcProvider provider = enabledProvider(slug);
        OidcClient.Discovery discovery = client.discover(provider.getIssuerUri());

        String state = randomToken();
        String nonce = randomToken();
        String verifier = randomToken() + randomToken();
        Map<String, String> values = new HashMap<>();
        values.put("slug", provider.getSlug());
        values.put("state", state);
        values.put("nonce", nonce);
        values.put("verifier", verifier);
        values.put("next", safeNext(next));
        String cookie = jwtService.issueOidcState(values, STATE_VALIDITY);

        String url = UriComponentsBuilder.fromUriString(discovery.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", enc(provider.getClientId()))
                .queryParam("redirect_uri", enc(redirectUri(provider, request)))
                .queryParam("scope", enc(scopes(provider)))
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "S256")
                .build(true)
                .toUriString();
        return new Start(url, cookie);
    }

    public Result complete(String slug, String code, String state, String stateCookie,
                           HttpServletRequest request) throws OidcException {
        Map<String, String> saved = jwtService.parseOidcState(stateCookie);
        if (saved == null) {
            throw new OidcException("state_expired", "Kein oder abgelaufener Anmelde-Zwischenstand (Cookie)");
        }
        if (!slug.equals(saved.get("slug")) || state == null || !state.equals(saved.get("state"))) {
            throw new OidcException("state_mismatch", "state passt nicht zum Cookie");
        }
        if (code == null || code.isBlank()) throw new OidcException("no_code", "Kein Code vom Anbieter");

        OidcProvider provider = enabledProvider(slug);
        OidcClient.Discovery discovery = client.discover(provider.getIssuerUri());
        Map<String, Object> tokens = client.exchangeCode(provider, discovery, code,
                redirectUri(provider, request), saved.get("verifier"));
        Object idToken = tokens.get("id_token");
        if (idToken == null) throw new OidcException("token_failed", "Antwort ohne id_token");
        Map<String, Object> claims = new HashMap<>(
                client.validateIdToken(idToken.toString(), discovery, provider.getClientId(), saved.get("nonce")));

        // Fehlen Name oder E-Mail im ID-Token, hilft der userinfo-Endpunkt aus -
        // aber nur mit demselben sub, sonst gehoert die Antwort nicht dazu.
        if (!claims.containsKey("email") || !claims.containsKey("name")) {
            Object accessToken = tokens.get("access_token");
            Map<String, Object> info = client.userInfo(discovery, accessToken == null ? null : accessToken.toString());
            if (claims.get("sub") != null && claims.get("sub").equals(info.get("sub"))) {
                info.forEach(claims::putIfAbsent);
            }
        }

        AppUser user = userMapper.resolve(provider, claims);
        log.info("Anmeldung von {} ueber {}", user.getUsername(), provider.getSlug());
        return new Result(jwtService.issue(user.getUsername()), user, saved.get("next"));
    }

    /**
     * Redirect-URI, die beim Anbieter eingetragen sein muss. Aus PUBLIC_URL,
     * sonst aus der Anfrage (dank forward-headers-strategy mit Schema/Host des Proxys).
     */
    public String redirectUri(OidcProvider provider, HttpServletRequest request) {
        return baseUrl(request) + "/api/auth/oidc/" + provider.getSlug() + "/callback";
    }

    public String baseUrl(HttpServletRequest request) {
        String configured = props.getAuth().getPublicUrl();
        if (configured != null && !configured.isBlank()) return OidcClient.trimSlash(configured);
        int port = request.getServerPort();
        boolean defaultPort = port <= 0
                || ("http".equalsIgnoreCase(request.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(request.getScheme()) && port == 443);
        return request.getScheme() + "://" + request.getServerName() + (defaultPort ? "" : ":" + port)
                + request.getContextPath();
    }

    private OidcProvider enabledProvider(String slug) throws OidcException {
        return providerRepo.findBySlug(slug)
                .filter(OidcProvider::isEnabled)
                .orElseThrow(() -> new OidcException("unknown_provider", "Anbieter " + slug + " unbekannt oder aus"));
    }

    private static String scopes(OidcProvider provider) {
        String scopes = provider.getScopes() == null ? "" : provider.getScopes().trim();
        if (!(" " + scopes + " ").contains(" openid ")) scopes = ("openid " + scopes).trim();
        return scopes;
    }

    /** Nur Pfade innerhalb der Anwendung - kein offener Redirect nach aussen. */
    static String safeNext(String next) {
        if (next == null || next.isBlank()) return "/";
        String n = next.trim();
        if (!n.startsWith("/") || n.startsWith("//") || n.startsWith("/\\") || n.contains("\r") || n.contains("\n")) {
            return "/";
        }
        return n;
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
