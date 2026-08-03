package bbbbot.auth;

import bbbbot.domain.ApiKey;
import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.ApiKeyRepo;
import bbbbot.repository.Repositories.AppUserRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Der Filter ist die Stelle, an der ein API-Schluessel Rechte bekommt - und die
 * beiden Sperren (nur lesen, keine Schluesselverwaltung) muessen dort halten.
 */
class ApiKeyAuthFilterTest {

    private final List<ApiKey> stored = new ArrayList<>();
    private AppUser user;
    private ApiKeyService keyService;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setup() {
        user = AppUser.create("skripter", "Skripterin", "skript@example.org");
        ApiKeyRepo keyRepo = mock(ApiKeyRepo.class);
        AppUserRepo userRepo = mock(AppUserRepo.class);
        keyService = new ApiKeyService(keyRepo);
        filter = new ApiKeyAuthFilter(keyService, userRepo);

        when(keyRepo.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey key = inv.getArgument(0);
            if (!stored.contains(key)) stored.add(key);
            return key;
        });
        when(keyRepo.findByTokenHash(anyString())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            return stored.stream().filter(k -> k.getTokenHash().equals(hash)).findFirst();
        });
        when(userRepo.findById(user.getId())).thenReturn(java.util.Optional.of(user));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private String key(boolean readOnly) {
        return keyService.create(user.getId(), readOnly ? "Nur lesen" : "Vollzugriff", readOnly, null)
                .token();
    }

    private MockHttpServletResponse run(String method, String path, String header, String value)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (header != null) request.addHeader(header, value);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void meldetDenNutzerMitGueltigemSchluesselAn() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/recordings",
                ApiKeyAuthFilter.HEADER, key(false));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isSameAs(user);
    }

    @Test
    void akzeptiertDenSchluesselAuchAlsBearerToken() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/recordings",
                "Authorization", "Bearer " + key(false));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void laesstLoginTokenUnberuehrt() throws Exception {
        // Ein JWT hat kein bbb_-Prefix: Der Filter darf es nicht anfassen,
        // sonst wuerde jede Anmeldung der Weboberflaeche hier scheitern.
        MockHttpServletResponse response = run("GET", "/api/recordings",
                "Authorization", "Bearer eyJhbGciOiJIUzM4NCJ9.abc.def");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ohneSchluesselBleibtDerAufrufUnverandert() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/recordings", null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unbekannterSchluesselWirdMitMeldungAbgewiesen() throws Exception {
        MockHttpServletResponse response = run("GET", "/api/recordings",
                ApiKeyAuthFilter.HEADER, "bbb_gibtEsNicht");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("API-Schluessel");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nurLeseSchluesselDarfLesenAberNichtSchreiben() throws Exception {
        String readOnly = key(true);

        assertThat(run("GET", "/api/glossary", ApiKeyAuthFilter.HEADER, readOnly).getStatus())
                .isEqualTo(200);

        MockHttpServletResponse write = run("POST", "/api/glossary", ApiKeyAuthFilter.HEADER, readOnly);
        assertThat(write.getStatus()).isEqualTo(403);
        assertThat(write.getContentAsString()).contains("nur lesen");

        assertThat(run("DELETE", "/api/glossary/1", ApiKeyAuthFilter.HEADER, readOnly).getStatus())
                .isEqualTo(403);
    }

    @Test
    void schluesselverwaltungIstPerSchluesselGesperrt() throws Exception {
        String full = key(false);

        // Auch lesend gesperrt: Ein abgeflossener Schluessel soll sich nicht
        // selbst verlaengern oder weitere Schluessel anlegen koennen.
        assertThat(run("GET", "/api/api-keys", ApiKeyAuthFilter.HEADER, full).getStatus())
                .isEqualTo(403);
        assertThat(run("POST", "/api/api-keys", ApiKeyAuthFilter.HEADER, full).getStatus())
                .isEqualTo(403);
        assertThat(run("DELETE", "/api/api-keys/" + UUID.randomUUID(), ApiKeyAuthFilter.HEADER, full)
                .getStatus()).isEqualTo(403);
        assertThat(run("POST", "/api/auth/change-password", ApiKeyAuthFilter.HEADER, full).getStatus())
                .isEqualTo(403);
    }

    @Test
    void gibtAdminrolleWeiterWennDerNutzerAdminIst() throws Exception {
        user.setAdmin(true);

        run("GET", "/api/admin/settings", ApiKeyAuthFilter.HEADER, key(false));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).contains("ROLE_ADMIN");
    }
}
