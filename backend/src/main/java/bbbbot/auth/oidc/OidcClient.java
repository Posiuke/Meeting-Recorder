package bbbbot.auth.oidc;

import bbbbot.domain.OidcProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Netzwerkseite von OIDC: Discovery, Code-gegen-Token-Tausch und Pruefung des
 * ID-Tokens (Signatur gegen die JWKS des Anbieters, Issuer, Audience, Ablauf,
 * Nonce). Discovery-Dokumente und Schluessel werden zwischengespeichert.
 */
@Component
public class OidcClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DISCOVERY_TTL = Duration.ofHours(1);
    /** Erlaubte Abweichung der Uhren zwischen Anbieter und Recorder. */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Cached> discoveryCache = new ConcurrentHashMap<>();
    private final Map<String, NimbusJwtDecoder> decoders = new ConcurrentHashMap<>();

    /** Endpunkte aus dem Discovery-Dokument. */
    public record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint,
                            String jwksUri, String userinfoEndpoint) {}

    private record Cached(Discovery discovery, Instant fetchedAt) {}

    public Discovery discover(String issuerUri) throws OidcException {
        String issuer = trimSlash(issuerUri);
        Cached cached = discoveryCache.get(issuer);
        if (cached != null && cached.fetchedAt.plus(DISCOVERY_TTL).isAfter(Instant.now())) {
            return cached.discovery;
        }
        JsonNode doc = getJson(issuer + "/.well-known/openid-configuration", "discovery_failed");
        Discovery discovery = new Discovery(
                text(doc, "issuer"),
                text(doc, "authorization_endpoint"),
                text(doc, "token_endpoint"),
                text(doc, "jwks_uri"),
                text(doc, "userinfo_endpoint"));
        if (discovery.authorizationEndpoint() == null || discovery.tokenEndpoint() == null
                || discovery.jwksUri() == null || discovery.issuer() == null) {
            throw new OidcException("discovery_failed",
                    "Discovery-Dokument von " + issuer + " ist unvollstaendig");
        }
        discoveryCache.put(issuer, new Cached(discovery, Instant.now()));
        return discovery;
    }

    /** Tauscht den Code gegen Tokens (client_secret_post plus PKCE). */
    public Map<String, Object> exchangeCode(OidcProvider provider, Discovery discovery, String code,
                                            String redirectUri, String codeVerifier) throws OidcException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", provider.getClientId());
        if (provider.getClientSecret() != null && !provider.getClientSecret().isBlank()) {
            form.put("client_secret", provider.getClientSecret());
        }
        form.put("code_verifier", codeVerifier);
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(discovery.tokenEndpoint()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        JsonNode json = send(request, "token_failed");
        if (json.hasNonNull("error")) {
            throw new OidcException("token_failed", "Token-Endpunkt: " + json.path("error").asText()
                    + " " + json.path("error_description").asText(""));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = mapper.convertValue(json, Map.class);
        return tokens;
    }

    /**
     * Prueft das ID-Token und liefert seine Claims.
     *
     * <p>Issuer: Multi-Tenant-Konfigurationen bei Microsoft (".../common/v2.0",
     * ".../organizations/v2.0") liefern im Discovery-Dokument einen Platzhalter
     * {@code {tenantid}}; er wird durch den Claim {@code tid} ersetzt.
     */
    public Map<String, Object> validateIdToken(String idToken, Discovery discovery, String clientId,
                                               String expectedNonce) throws OidcException {
        Jwt jwt;
        try {
            jwt = decoder(discovery.jwksUri()).decode(idToken);
        } catch (JwtException e) {
            throw new OidcException("invalid_token", "ID-Token ungueltig: " + e.getMessage());
        }
        Map<String, Object> claims = jwt.getClaims();

        String expectedIssuer = discovery.issuer();
        if (expectedIssuer.contains("{tenantid}")) {
            Object tid = claims.get("tid");
            if (tid == null) throw new OidcException("invalid_token", "ID-Token ohne tid");
            expectedIssuer = expectedIssuer.replace("{tenantid}", tid.toString());
        }
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        if (issuer == null || !trimSlash(issuer).equals(trimSlash(expectedIssuer))) {
            throw new OidcException("invalid_token", "Unerwarteter Issuer: " + issuer);
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(clientId)) {
            throw new OidcException("invalid_token", "ID-Token ist nicht fuer diese Anwendung ausgestellt");
        }
        Instant now = Instant.now();
        if (jwt.getExpiresAt() == null || jwt.getExpiresAt().plus(CLOCK_SKEW).isBefore(now)) {
            throw new OidcException("invalid_token", "ID-Token abgelaufen");
        }
        if (jwt.getIssuedAt() != null && jwt.getIssuedAt().minus(CLOCK_SKEW).isAfter(now)) {
            throw new OidcException("invalid_token", "ID-Token aus der Zukunft");
        }
        if (expectedNonce == null || !expectedNonce.equals(claims.get("nonce"))) {
            throw new OidcException("invalid_token", "Nonce stimmt nicht");
        }
        return claims;
    }

    /** Ergaenzende Claims vom userinfo-Endpunkt; leer, wenn es keinen gibt oder er scheitert. */
    public Map<String, Object> userInfo(Discovery discovery, String accessToken) {
        if (discovery.userinfoEndpoint() == null || accessToken == null) return Map.of();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(discovery.userinfoEndpoint()))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            JsonNode json = send(request, "userinfo_failed");
            @SuppressWarnings("unchecked")
            Map<String, Object> info = mapper.convertValue(json, Map.class);
            return info;
        } catch (OidcException | IllegalArgumentException e) {
            return Map.of();
        }
    }

    private NimbusJwtDecoder decoder(String jwksUri) {
        return decoders.computeIfAbsent(jwksUri, uri -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(uri)
                    .jwsAlgorithms(algs -> {
                        algs.add(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256);
                        algs.add(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.PS256);
                        algs.add(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256);
                    })
                    .build();
            // Eigene Pruefungen in validateIdToken (u.a. Microsoft-Issuer-Platzhalter);
            // hier nur die Signatur.
            decoder.setJwtValidator(token -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
            return decoder;
        });
    }

    private JsonNode getJson(String url, String errorCode) throws OidcException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("Accept", "application/json").GET().build();
        } catch (IllegalArgumentException e) {
            throw new OidcException(errorCode, "Ungueltige Adresse: " + url);
        }
        return send(request, errorCode);
    }

    private JsonNode send(HttpRequest request, String errorCode) throws OidcException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json;
            try {
                json = mapper.readTree(response.body());
            } catch (IOException e) {
                throw new OidcException(errorCode, request.uri().getHost() + " antwortet mit HTTP "
                        + response.statusCode() + " ohne JSON");
            }
            if (response.statusCode() >= 400 && !json.hasNonNull("error")) {
                throw new OidcException(errorCode, request.uri().getHost() + " antwortet mit HTTP "
                        + response.statusCode());
            }
            return json;
        } catch (IOException e) {
            throw new OidcException(errorCode, request.uri().getHost() + " nicht erreichbar: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OidcException(errorCode, "abgebrochen");
        }
    }

    private static String text(JsonNode doc, String field) {
        JsonNode node = doc.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    static String trimSlash(String s) {
        if (s == null) return null;
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Hilfsfunktion fuer Claims, die als Liste oder Einzelwert kommen. */
    static List<String> asStrings(Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        String s = value.toString().trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }
}
