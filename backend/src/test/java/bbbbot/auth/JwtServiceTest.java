package bbbbot.auth;

import bbbbot.config.AppProperties;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtServiceTest {

    private static final String SECRET = "test-secret-mit-mindestens-32-zeichen-laenge!!";

    private JwtService service() {
        AppProperties props = new AppProperties();
        props.getAuth().setJwtSecret(SECRET);
        // jwtTtlHours bewusst NICHT setzen -> Default muss 7 Tage sein
        return new JwtService(props);
    }

    @Test
    void standardTtlIstSiebenTage() {
        String token = service().issue("admin");
        String payloadJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8);
        // iat/exp sind Unix-Sekunden im JSON-Payload
        long iat = extract(payloadJson, "iat");
        long exp = extract(payloadJson, "exp");
        assertEquals(7 * 24 * 3600, exp - iat, "Login-Session muss standardmaessig 7 Tage gelten");
    }

    @Test
    void abgelaufenerTokenWirdAbgelehnt() {
        String expired = Jwts.builder()
                .subject("admin")
                .issuedAt(new Date(System.currentTimeMillis() - 7200_000))
                .expiration(new Date(System.currentTimeMillis() - 3600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertNull(service().validate(expired), "Abgelaufener Token darf nicht validieren");
    }

    @Test
    void gueltigerTokenValidiert() {
        JwtService jwt = service();
        assertEquals("admin", jwt.validate(jwt.issue("admin")));
    }

    private static long extract(String json, String claim) {
        var m = java.util.regex.Pattern.compile("\"" + claim + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalStateException(claim + " fehlt im Payload: " + json);
        return Long.parseLong(m.group(1));
    }
}
