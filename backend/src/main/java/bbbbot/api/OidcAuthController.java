package bbbbot.api;

import bbbbot.auth.oidc.OidcException;
import bbbbot.auth.oidc.OidcLoginService;
import bbbbot.repository.Repositories.OidcProviderRepo;
import bbbbot.settings.AuthSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Oeffentliche Endpunkte der OIDC-Anmeldung. Ablauf siehe {@link OidcLoginService}.
 * Start und Rueckkehr sind Browser-Navigationen (keine XHR), deshalb antworten
 * sie mit Weiterleitungen - am Ende auf {@code /login/sso#token=...}; das Token
 * steht im Fragment und landet so weder in Server-Logs noch im Referer.
 */
@RestController
@RequestMapping("/api/auth")
public class OidcAuthController {

    private static final Logger log = LoggerFactory.getLogger(OidcAuthController.class);

    static final String STATE_COOKIE = "mr_oidc_state";
    private static final String COOKIE_PATH = "/api/auth/oidc";

    private final OidcLoginService loginService;
    private final OidcProviderRepo providerRepo;
    private final AuthSettingsService authSettings;

    public OidcAuthController(OidcLoginService loginService, OidcProviderRepo providerRepo,
                              AuthSettingsService authSettings) {
        this.loginService = loginService;
        this.providerRepo = providerRepo;
        this.authSettings = authSettings;
    }

    public record ProviderButton(String slug, String displayName) {}

    public record LoginOptions(List<ProviderButton> providers, boolean localLoginHidden) {}

    /** Was die Anmeldeseite anbietet: Knoepfe je Anbieter, ggf. ohne Passwortformular. */
    @GetMapping("/options")
    public LoginOptions options() {
        List<ProviderButton> providers = providerRepo.findByEnabledTrueOrderByDisplayNameAsc().stream()
                .map(p -> new ProviderButton(p.getSlug(), p.getDisplayName()))
                .toList();
        // Ohne Anbieter waere sonst gar keine Anmeldung moeglich.
        return new LoginOptions(providers, !providers.isEmpty() && authSettings.isLocalLoginHidden());
    }

    @GetMapping("/oidc/{slug}/start")
    public void start(@PathVariable String slug, @RequestParam(value = "next", required = false) String next,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            OidcLoginService.Start start = loginService.start(slug, next, request);
            response.addHeader(HttpHeaders.SET_COOKIE, stateCookie(start.stateCookie(), request,
                    OidcLoginService.STATE_VALIDITY.toSeconds()).toString());
            response.sendRedirect(start.authorizationUrl());
        } catch (OidcException e) {
            log.warn("OIDC-Start {} gescheitert: {}", slug, e.getMessage());
            response.sendRedirect(loginService.baseUrl(request) + "/login?sso_error=" + e.code());
        }
    }

    @GetMapping("/oidc/{slug}/callback")
    public void callback(@PathVariable String slug,
                         @RequestParam(value = "code", required = false) String code,
                         @RequestParam(value = "state", required = false) String state,
                         @RequestParam(value = "error", required = false) String error,
                         @RequestParam(value = "error_description", required = false) String errorDescription,
                         @CookieValue(value = STATE_COOKIE, required = false) String stateCookie,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Zwischenstand ist nur einmal gueltig
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie("", request, 0).toString());
        String base = loginService.baseUrl(request);
        if (error != null) {
            log.warn("OIDC {}: Anbieter meldet {} ({})", slug, error, errorDescription);
            String codeForUi = "access_denied".equals(error) ? "access_denied" : "provider_error";
            response.sendRedirect(base + "/login?sso_error=" + codeForUi);
            return;
        }
        try {
            OidcLoginService.Result result = loginService.complete(slug, code, state, stateCookie, request);
            response.sendRedirect(base + "/login/sso#token=" + enc(result.token()) + "&next=" + enc(result.next()));
        } catch (OidcException e) {
            log.warn("OIDC-Anmeldung ueber {} gescheitert ({}): {}", slug, e.code(), e.getMessage());
            response.sendRedirect(base + "/login?sso_error=" + e.code());
        }
    }

    private static ResponseCookie stateCookie(String value, HttpServletRequest request, long maxAgeSeconds) {
        return ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true)
                .secure(request.isSecure())
                // Lax: Die Rueckkehr vom Anbieter ist eine Top-Level-Navigation (GET)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
