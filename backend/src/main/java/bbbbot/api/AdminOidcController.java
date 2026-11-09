package bbbbot.api;

import bbbbot.auth.oidc.OidcClient;
import bbbbot.auth.oidc.OidcException;
import bbbbot.auth.oidc.OidcLoginService;
import bbbbot.domain.OidcProvider;
import bbbbot.repository.Repositories.OidcProviderRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pflege der OIDC-Anbieter (nur Admins, siehe SecurityConfig). Das
 * Client-Secret verlaesst den Server nie wieder: Die Ansicht meldet nur, ob
 * eines hinterlegt ist; ein leeres Feld beim Speichern behaelt das alte.
 */
@RestController
@RequestMapping("/api/admin/oidc-providers")
public class AdminOidcController {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final OidcProviderRepo repo;
    private final OidcLoginService loginService;
    private final OidcClient client;

    public AdminOidcController(OidcProviderRepo repo, OidcLoginService loginService, OidcClient client) {
        this.repo = repo;
        this.loginService = loginService;
        this.client = client;
    }

    public record ProviderView(UUID id, String slug, String displayName, boolean enabled, String issuerUri,
                               String clientId, boolean hasClientSecret, String scopes, String usernameClaim,
                               String groupsClaim, String adminValues, String allowedValues, String redirectUri) {}

    public record ProviderRequest(String slug, String displayName, Boolean enabled, String issuerUri,
                                  String clientId, String clientSecret, String scopes, String usernameClaim,
                                  String groupsClaim, String adminValues, String allowedValues) {}

    public record TestResult(boolean ok, String message) {}

    @GetMapping
    public List<ProviderView> list(HttpServletRequest request) {
        return repo.findAllByOrderByDisplayNameAsc().stream().map(p -> view(p, request)).toList();
    }

    @PostMapping
    public ProviderView create(@RequestBody ProviderRequest body, HttpServletRequest request) {
        String slug = body.slug() == null ? "" : body.slug().trim().toLowerCase();
        if (!SLUG.matcher(slug).matches()) {
            throw bad("Kennung: nur Kleinbuchstaben, Ziffern und Bindestrich (max. 40 Zeichen)");
        }
        if (repo.findBySlug(slug).isPresent()) throw bad("Kennung '" + slug + "' ist schon vergeben");
        OidcProvider provider = OidcProvider.create(slug);
        apply(provider, body);
        return view(repo.save(provider), request);
    }

    @PutMapping("/{id}")
    public ProviderView update(@PathVariable UUID id, @RequestBody ProviderRequest body,
                               HttpServletRequest request) {
        OidcProvider provider = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Anbieter nicht gefunden"));
        // Die Kennung steckt in der beim Anbieter eingetragenen Redirect-URI und
        // bleibt deshalb fest.
        apply(provider, body);
        return view(repo.save(provider), request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        repo.deleteById(id);
    }

    /** Prueft Erreichbarkeit und Discovery-Dokument des Anbieters. */
    @PostMapping("/{id}/test")
    public TestResult test(@PathVariable UUID id) {
        OidcProvider provider = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Anbieter nicht gefunden"));
        try {
            OidcClient.Discovery d = client.discover(provider.getIssuerUri());
            return new TestResult(true, "Discovery ok. Issuer: " + d.issuer()
                    + ", Anmeldung: " + d.authorizationEndpoint());
        } catch (OidcException e) {
            return new TestResult(false, e.getMessage());
        }
    }

    private void apply(OidcProvider p, ProviderRequest b) {
        String displayName = trim(b.displayName());
        String issuer = trim(b.issuerUri());
        String clientId = trim(b.clientId());
        if (displayName == null) throw bad("Anzeigename fehlt");
        if (issuer == null || !(issuer.startsWith("https://") || issuer.startsWith("http://"))) {
            throw bad("Issuer muss eine http(s)-Adresse sein");
        }
        if (clientId == null) throw bad("Client-ID fehlt");
        p.setDisplayName(displayName);
        p.setIssuerUri(issuer);
        p.setClientId(clientId);
        if (b.enabled() != null) p.setEnabled(b.enabled());
        if (b.clientSecret() != null && !b.clientSecret().isBlank()) p.setClientSecret(b.clientSecret().trim());
        p.setScopes(trim(b.scopes()) == null ? "openid profile email" : trim(b.scopes()));
        p.setUsernameClaim(trim(b.usernameClaim()) == null ? "preferred_username" : trim(b.usernameClaim()));
        p.setGroupsClaim(trim(b.groupsClaim()));
        p.setAdminValues(trim(b.adminValues()));
        p.setAllowedValues(trim(b.allowedValues()));
        if ((p.getAdminValues() != null || p.getAllowedValues() != null) && p.getGroupsClaim() == null) {
            throw bad("Fuer Admin- oder Zugangswerte muss der Gruppen-Claim gesetzt sein");
        }
    }

    private ProviderView view(OidcProvider p, HttpServletRequest request) {
        return new ProviderView(p.getId(), p.getSlug(), p.getDisplayName(), p.isEnabled(), p.getIssuerUri(),
                p.getClientId(), p.getClientSecret() != null && !p.getClientSecret().isBlank(), p.getScopes(),
                p.getUsernameClaim(), p.getGroupsClaim(), p.getAdminValues(), p.getAllowedValues(),
                loginService.redirectUri(p, request));
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
