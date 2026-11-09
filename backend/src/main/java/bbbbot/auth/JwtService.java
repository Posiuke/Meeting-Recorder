package bbbbot.auth;

import bbbbot.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        String secret = props.getAuth().getJwtSecret();
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            this.key = Keys.hmacShaKeyFor(random);
            log.warn("Kein JWT_SECRET konfiguriert - fluechtiger Schluessel erzeugt. "
                    + "Alle Sessions werden bei einem Neustart ungueltig!");
        } else {
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.ttl = Duration.ofHours(props.getAuth().getJwtTtlHours());
    }

    public String issue(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Liefert den Benutzernamen oder null bei ungueltigem/abgelaufenem Token. */
    public String validate(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            // Andere mit demselben Schluessel signierte Tokens (OIDC-Zwischenstand)
            // sind keine Anmeldung.
            if (claims.get(TYPE_CLAIM) != null) return null;
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    static final String TYPE_CLAIM = "typ";
    private static final String OIDC_STATE_TYPE = "oidc-state";

    /**
     * Kurzlebiger, signierter Zwischenstand einer OIDC-Anmeldung (state, nonce,
     * PKCE-Verifier, Ziel). Liegt als Cookie im Browser - so bleibt das Backend
     * zustandslos, und auch ein Neustart zwischen Hin- und Rueckweg schadet nicht.
     */
    public String issueOidcState(Map<String, String> values, Duration validity) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(values)
                .claim(TYPE_CLAIM, OIDC_STATE_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .signWith(key)
                .compact();
    }

    /** Werte des Zwischenstands; null, wenn ungueltig, abgelaufen oder falscher Typ. */
    public Map<String, String> parseOidcState(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!OIDC_STATE_TYPE.equals(claims.get(TYPE_CLAIM))) return null;
            Map<String, String> values = new java.util.HashMap<>();
            claims.forEach((k, v) -> {
                if (v instanceof String str) values.put(k, str);
            });
            return values;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
