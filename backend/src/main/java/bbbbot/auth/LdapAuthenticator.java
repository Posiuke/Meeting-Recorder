package bbbbot.auth;

import bbbbot.settings.AuthSettingsService;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.LdapUserDetails;
import org.springframework.security.ldap.userdetails.LdapUserDetailsMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Fuehrt LDAP/Active-Directory-Anmeldungen dynamisch anhand der DB-Konfiguration
 * ({@link AuthSettingsService}) durch. Der AD-Provider wird pro Anmeldung/Test
 * neu aufgebaut, damit Aenderungen an den Einstellungen sofort greifen (kein
 * statischer Bean beim Start mehr).
 */
@Component
public class LdapAuthenticator {

    private final AuthSettingsService settings;

    public LdapAuthenticator(AuthSettingsService settings) {
        this.settings = settings;
    }

    /** Ergebnis einer erfolgreichen LDAP-Anmeldung. */
    public record LdapUser(String username, String displayName, String email) {}

    /**
     * Bind gegen das AD mit den aktuellen DB-Einstellungen.
     * Wirft {@link org.springframework.security.core.AuthenticationException} bei
     * falschen Zugangsdaten und {@link IllegalStateException}, wenn LDAP nicht
     * konfiguriert ist (Domain/URL fehlen).
     */
    public LdapUser authenticate(String username, String password) {
        ActiveDirectoryLdapAuthenticationProvider provider = buildProvider();
        Authentication auth = provider.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        String displayName = null;
        String email = null;
        if (auth.getPrincipal() instanceof AdUserDetails ad) {
            displayName = ad.displayName();
            email = ad.email();
        }
        return new LdapUser(auth.getName(), displayName, email);
    }

    private ActiveDirectoryLdapAuthenticationProvider buildProvider() {
        String domain = settings.domain();
        String url = settings.url();
        String rootDn = settings.rootDn();
        if (domain.isBlank() || url.isBlank()) {
            throw new IllegalStateException(
                    "LDAP ist nicht konfiguriert - Domain und URL sind erforderlich.");
        }
        ActiveDirectoryLdapAuthenticationProvider provider = rootDn.isBlank()
                ? new ActiveDirectoryLdapAuthenticationProvider(domain, url)
                : new ActiveDirectoryLdapAuthenticationProvider(domain, url, rootDn);
        provider.setConvertSubErrorCodesToExceptions(true);
        provider.setUserDetailsContextMapper(new AdAttributeMapper());
        return provider;
    }

    static String attr(DirContextOperations ctx, String name) {
        try {
            return ctx.getStringAttribute(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Nimmt displayName und mail aus dem AD-Eintrag mit. */
    static class AdAttributeMapper extends LdapUserDetailsMapper {
        @Override
        public UserDetails mapUserFromContext(DirContextOperations ctx, String username,
                                              Collection<? extends GrantedAuthority> authorities) {
            UserDetails base = super.mapUserFromContext(ctx, username, authorities);
            String displayName = attr(ctx, "displayName");
            String mail = attr(ctx, "mail");
            return new AdUserDetails((LdapUserDetails) base, displayName, mail);
        }
    }

    /** LdapUserDetails-Wrapper mit AD-Zusatzattributen. */
    public record AdUserDetails(LdapUserDetails delegate, String displayName, String email)
            implements LdapUserDetails {
        @Override public String getDn() { return delegate.getDn(); }
        @Override public Collection<? extends GrantedAuthority> getAuthorities() { return delegate.getAuthorities(); }
        @Override public String getPassword() { return delegate.getPassword(); }
        @Override public String getUsername() { return delegate.getUsername(); }
        @Override public boolean isAccountNonExpired() { return delegate.isAccountNonExpired(); }
        @Override public boolean isAccountNonLocked() { return delegate.isAccountNonLocked(); }
        @Override public boolean isCredentialsNonExpired() { return delegate.isCredentialsNonExpired(); }
        @Override public boolean isEnabled() { return delegate.isEnabled(); }
        @Override public void eraseCredentials() { delegate.eraseCredentials(); }
    }
}
