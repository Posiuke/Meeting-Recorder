package bbbbot.auth;

import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.settings.AuthSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lokale Konten, die ein Admin anlegt (ohne LDAP). */
class LocalUserTest {

    private AppUserRepo userRepo;
    private AuthService auth;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        userRepo = mock(AppUserRepo.class);
        when(userRepo.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        auth = new AuthService(userRepo, mock(JwtService.class), encoder,
                mock(AuthSettingsService.class), mock(LdapAuthenticator.class));
    }

    @Test
    void legtLokalesKontoMitPasswortwechselBeimErstenLoginAn() {
        AppUser user = auth.createLocalUser(" anna.m ", "", "anna@example.org", "geheim123", false);

        assertThat(user.getUsername()).isEqualTo("anna.m");
        assertThat(user.getDisplayName()).isEqualTo("anna.m");
        assertThat(user.getEmail()).isEqualTo("anna@example.org");
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.isMustChangePassword()).isTrue();
        assertThat(encoder.matches("geheim123", user.getPasswordHash())).isTrue();
    }

    @Test
    void lehntVergebenenNamenKurzesPasswortUndLeerzeichenAb() {
        when(userRepo.findByUsernameIgnoreCase("Tom")).thenReturn(Optional.of(AppUser.create("tom", "Tom", null)));

        assertThatThrownBy(() -> auth.createLocalUser("Tom", null, null, "geheim123", false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> auth.createLocalUser("eva", null, null, "kurz", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("8 Zeichen");
        assertThatThrownBy(() -> auth.createLocalUser("eva maier", null, null, "geheim123", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwortResetNurFuerLokaleKonten() {
        AppUser local = AppUser.create("anna", "Anna", null);
        local.setPasswordHash(encoder.encode("altesPasswort"));
        AppUser ldapUser = AppUser.create("max", "Max", null);
        UUID localId = local.getId();
        UUID ldapId = ldapUser.getId();
        when(userRepo.findById(localId)).thenReturn(Optional.of(local));
        when(userRepo.findById(ldapId)).thenReturn(Optional.of(ldapUser));

        AppUser reset = auth.resetPassword(localId, "neuesPasswort");

        assertThat(encoder.matches("neuesPasswort", reset.getPasswordHash())).isTrue();
        assertThat(reset.isMustChangePassword()).isTrue();
        assertThatThrownBy(() -> auth.resetPassword(ldapId, "neuesPasswort"))
                .isInstanceOf(IllegalStateException.class);
    }
}
