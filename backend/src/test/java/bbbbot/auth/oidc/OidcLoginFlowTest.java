package bbbbot.auth.oidc;

import bbbbot.auth.JwtService;
import bbbbot.config.AppProperties;
import bbbbot.domain.AppUser;
import bbbbot.domain.OidcProvider;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.OidcProviderRepo;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kompletter Ablauf gegen einen nachgebauten Anbieter (Discovery, JWKS,
 * Token-Endpunkt) - mit echter Signaturpruefung, PKCE und Nonce.
 */
class OidcLoginFlowTest {

    private HttpServer idp;
    private String issuer;
    private RSAKey key;
    /** Womit signiert wird; normalerweise der veroeffentlichte Schluessel. */
    private RSAKey signingKey;
    private final AtomicReference<Map<String, String>> lastTokenRequest = new AtomicReference<>();
    /** Was der Anbieter ins naechste ID-Token schreibt (nonce kommt aus der Anmeldung). */
    private final Map<String, Object> nextClaims = new HashMap<>();
    private String pendingNonce;
    private String pendingChallenge;

    private OidcProvider provider;
    private OidcLoginService service;
    private AppUserRepo userRepo;
    private JwtService jwt;
    private final Map<String, AppUser> users = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        signingKey = key;
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + idp.getAddress().getPort() + "/tenant";
        idp.createContext("/tenant/.well-known/openid-configuration", ex -> respond(ex, """
                {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
                 "jwks_uri":"%s/keys"}""".formatted(issuer, issuer, issuer, issuer)));
        idp.createContext("/tenant/keys", ex -> respond(ex, new JWKSet(key.toPublicJWK()).toString()));
        idp.createContext("/tenant/token", ex -> {
            Map<String, String> form = parseForm(ex.getRequestBody());
            lastTokenRequest.set(form);
            if (!OidcLoginService.challenge(form.get("code_verifier")).equals(pendingChallenge)) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            respond(ex, "{\"id_token\":\"" + idToken(nextClaims) + "\",\"access_token\":\"at\"}");
        });
        idp.start();

        provider = OidcProvider.create("microsoft");
        provider.setDisplayName("Microsoft");
        provider.setIssuerUri(issuer + "/");
        provider.setClientId("client-123");
        provider.setClientSecret("geheim");
        provider.setGroupsClaim("roles");

        OidcProviderRepo providerRepo = mock(OidcProviderRepo.class);
        when(providerRepo.findBySlug("microsoft")).thenAnswer(i -> Optional.of(provider));
        userRepo = mock(AppUserRepo.class);
        when(userRepo.findByAuthProviderAndExternalSubject(anyString(), anyString())).thenAnswer(i ->
                users.values().stream()
                        .filter(u -> i.getArgument(0).equals(u.getAuthProvider())
                                && i.getArgument(1).equals(u.getExternalSubject()))
                        .findFirst());
        when(userRepo.findByUsernameIgnoreCase(anyString())).thenAnswer(i ->
                Optional.ofNullable(users.get(((String) i.getArgument(0)).toLowerCase())));
        when(userRepo.save(any(AppUser.class))).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            users.put(u.getUsername().toLowerCase(), u);
            return u;
        });

        AppProperties props = new AppProperties();
        props.getAuth().setJwtSecret("test-secret-test-secret-test-secret-1234");
        props.getAuth().setPublicUrl("https://rec.example.com/");
        jwt = new JwtService(props);
        service = new OidcLoginService(providerRepo, new OidcClient(), new OidcUserMapper(userRepo), jwt, props);

        nextClaims.put("sub", "sub-anna");
        nextClaims.put("preferred_username", "anna@wikavis.de");
        nextClaims.put("name", "Anna Beispiel");
        nextClaims.put("email", "anna@wikavis.de");
    }

    @AfterEach
    void tearDown() {
        idp.stop(0);
    }

    @Test
    void vollstaendigeAnmeldungLegtKontoAnUndStelltEigenesJwtAus() throws Exception {
        OidcLoginService.Start start = startLogin("/recordings/42");

        Map<String, String> query = query(start.authorizationUrl());
        assertThat(start.authorizationUrl()).startsWith(issuer + "/authorize?");
        assertThat(query).containsEntry("client_id", "client-123")
                .containsEntry("redirect_uri", "https://rec.example.com/api/auth/oidc/microsoft/callback")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("response_type", "code");
        assertThat(query.get("scope")).contains("openid");

        OidcLoginService.Result result = service.complete("microsoft", "code-1", query.get("state"),
                start.stateCookie(), new MockHttpServletRequest());

        assertThat(result.next()).isEqualTo("/recordings/42");
        assertThat(jwt.validate(result.token())).isEqualTo("anna@wikavis.de");
        AppUser anna = users.get("anna@wikavis.de");
        assertThat(anna.getAuthProvider()).isEqualTo("microsoft");
        assertThat(anna.getExternalSubject()).isEqualTo("sub-anna");
        assertThat(anna.getDisplayName()).isEqualTo("Anna Beispiel");
        assertThat(lastTokenRequest.get()).containsEntry("client_secret", "geheim")
                .containsEntry("redirect_uri", "https://rec.example.com/api/auth/oidc/microsoft/callback");
    }

    @Test
    void zwischenstandIstKeinLoginToken() throws Exception {
        OidcLoginService.Start start = startLogin("/");
        assertThat(jwt.validate(start.stateCookie())).isNull();
    }

    @Test
    void falscherStateWirdAbgelehnt() throws Exception {
        OidcLoginService.Start start = startLogin("/");
        assertThatThrownBy(() -> service.complete("microsoft", "c", "anderer-state", start.stateCookie(),
                new MockHttpServletRequest()))
                .isInstanceOf(OidcException.class)
                .extracting(e -> ((OidcException) e).code()).isEqualTo("state_mismatch");
    }

    @Test
    void fehlendesCookieWirdAbgelehnt() {
        assertThatThrownBy(() -> service.complete("microsoft", "c", "s", null, new MockHttpServletRequest()))
                .extracting(e -> ((OidcException) e).code()).isEqualTo("state_expired");
    }

    @Test
    void falscheNonceWirdAbgelehnt() throws Exception {
        OidcLoginService.Start start = startLogin("/");
        pendingNonce = "manipuliert";
        assertThatThrownBy(() -> service.complete("microsoft", "c", query(start.authorizationUrl()).get("state"),
                start.stateCookie(), new MockHttpServletRequest()))
                .extracting(e -> ((OidcException) e).code()).isEqualTo("invalid_token");
    }

    @Test
    void tokenMitFremdemSchluesselWirdAbgelehnt() throws Exception {
        OidcLoginService.Start start = startLogin("/");
        signingKey = new RSAKeyGenerator(2048).keyID("k1").generate(); // gleiche kid, anderer Schluessel
        assertThatThrownBy(() -> service.complete("microsoft", "c", query(start.authorizationUrl()).get("state"),
                start.stateCookie(), new MockHttpServletRequest()))
                .extracting(e -> ((OidcException) e).code()).isEqualTo("invalid_token");
    }

    @Test
    void tokenFuerAndereAnwendungWirdAbgelehnt() throws Exception {
        OidcLoginService.Start start = startLogin("/");
        nextClaims.put("aud", "fremde-app");
        assertThatThrownBy(() -> service.complete("microsoft", "c", query(start.authorizationUrl()).get("state"),
                start.stateCookie(), new MockHttpServletRequest()))
                .extracting(e -> ((OidcException) e).code()).isEqualTo("invalid_token");
    }

    @Test
    void adminRechtUndZugangFolgenDenRollen() throws Exception {
        provider.setAdminValues("Recorder.Admin");
        provider.setAllowedValues("Recorder.User, Recorder.Admin");

        nextClaims.put("roles", List.of("Recorder.User", "Recorder.Admin"));
        login();
        assertThat(users.get("anna@wikavis.de").isAdmin()).isTrue();

        nextClaims.put("roles", List.of("Recorder.User"));
        login();
        assertThat(users.get("anna@wikavis.de").isAdmin()).as("Entzug beim naechsten Login").isFalse();

        nextClaims.put("roles", List.of("Sonstwas"));
        assertThatThrownBy(this::login).extracting(e -> ((OidcException) e).code()).isEqualTo("not_allowed");
    }

    @Test
    void ldapKontoMitGleichemNamenWirdUebernommenLokalesNicht() throws Exception {
        AppUser ldap = AppUser.create("anna@wikavis.de", "Anna (AD)", null);
        users.put("anna@wikavis.de", ldap);
        login();
        assertThat(users.get("anna@wikavis.de")).isSameAs(ldap);
        assertThat(ldap.getAuthProvider()).isEqualTo("microsoft");

        AppUser local = AppUser.create("bob@wikavis.de", "Bob", null);
        local.setPasswordHash("$2a$10$xyz");
        users.put("bob@wikavis.de", local);
        nextClaims.put("sub", "sub-bob");
        nextClaims.put("preferred_username", "bob@wikavis.de");
        assertThatThrownBy(this::login).extracting(e -> ((OidcException) e).code()).isEqualTo("username_taken");
        assertThat(local.getAuthProvider()).isNull();
    }

    @Test
    void umbenennungBeimAnbieterBehaeltDasKonto() throws Exception {
        login();
        AppUser first = users.get("anna@wikavis.de");
        nextClaims.put("preferred_username", "anna.neu@wikavis.de");
        nextClaims.put("name", "Anna Neu");
        login();
        // Wiedererkennung ueber sub: dasselbe Konto, nur der Anzeigename folgt
        assertThat(first.getDisplayName()).isEqualTo("Anna Neu");
        assertThat(users).doesNotContainKey("anna.neu@wikavis.de");
    }

    @Test
    void microsoftMultiTenantIssuerMitPlatzhalter() throws Exception {
        OidcClient client = new OidcClient();
        OidcClient.Discovery discovery = new OidcClient.Discovery(
                "https://login.microsoftonline.com/{tenantid}/v2.0", issuer + "/authorize", issuer + "/token",
                issuer + "/keys", null);
        Map<String, Object> claims = new HashMap<>(nextClaims);
        claims.put("iss", "https://login.microsoftonline.com/t-42/v2.0");
        claims.put("tid", "t-42");
        claims.put("nonce", "n1");
        claims.put("aud", "client-123");
        assertThat(client.validateIdToken(sign(claims), discovery, "client-123", "n1")).containsEntry("tid", "t-42");

        claims.put("tid", "t-anders");
        assertThatThrownBy(() -> client.validateIdToken(sign(claims), discovery, "client-123", "n1"))
                .isInstanceOf(OidcException.class);
    }

    @Test
    void nextErlaubtNurInterneZiele() {
        assertThat(OidcLoginService.safeNext("/recordings/1?x=2")).isEqualTo("/recordings/1?x=2");
        assertThat(OidcLoginService.safeNext("https://evil.example")).isEqualTo("/");
        assertThat(OidcLoginService.safeNext("//evil.example")).isEqualTo("/");
        assertThat(OidcLoginService.safeNext("/\\evil.example")).isEqualTo("/");
        assertThat(OidcLoginService.safeNext(null)).isEqualTo("/");
    }

    // ------------------------------------------------------------------

    private void login() throws Exception {
        OidcLoginService.Start start = startLogin("/");
        service.complete("microsoft", "code", query(start.authorizationUrl()).get("state"), start.stateCookie(),
                new MockHttpServletRequest());
    }

    private OidcLoginService.Start startLogin(String next) throws Exception {
        OidcLoginService.Start start = service.start("microsoft", next, new MockHttpServletRequest());
        Map<String, String> q = query(start.authorizationUrl());
        pendingNonce = q.get("nonce");
        pendingChallenge = q.get("code_challenge");
        return start;
    }

    private String idToken(Map<String, Object> claims) {
        Map<String, Object> all = new HashMap<>();
        all.put("iss", issuer);
        all.put("aud", "client-123");
        all.put("nonce", pendingNonce);
        all.putAll(claims);
        return sign(all);
    }

    private String sign(Map<String, Object> claims) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            claims.forEach(builder::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                    .type(JOSEObjectType.JWT).build(), builder.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(String url) {
        Map<String, String> q = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((k, v) -> q.put(k, URLDecoder.decode(v.get(0), StandardCharsets.UTF_8)));
        return q;
    }

    private static Map<String, String> parseForm(InputStream body) throws IOException {
        Map<String, String> form = new HashMap<>();
        for (String pair : new String(body.readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            String[] kv = pair.split("=", 2);
            form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return form;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
