package bbbbot.api;

import bbbbot.auth.LdapAuthenticator;
import bbbbot.auth.UserActivityService;
import bbbbot.domain.AppUser;
import bbbbot.domain.Recording;
import bbbbot.llm.LlmClient;
import bbbbot.media.FfmpegService;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.settings.AuthSettingsService;
import bbbbot.settings.SettingsService;
import bbbbot.stt.WhisperClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Die Nutzerliste des Admin-Bereichs: Sie soll beantworten, wer gerade
 * angemeldet ist und wessen Aufnahme ein Neustart zerreissen wuerde.
 */
class AdminUsersTest {

    private AppUserRepo userRepo;
    private RecordingRepo recordingRepo;
    private AdminController controller;

    private AppUser aktiv;
    private AppUser inaktiv;

    @BeforeEach
    void setup() {
        userRepo = mock(AppUserRepo.class);
        recordingRepo = mock(RecordingRepo.class);
        controller = new AdminController(mock(SettingsService.class), mock(AuthSettingsService.class),
                mock(LdapAuthenticator.class), userRepo, recordingRepo,
                mock(LlmClient.class), mock(WhisperClient.class), mock(FfmpegService.class),
                mock(bbbbot.docs.TikaClient.class));

        aktiv = AppUser.create("zzz.aktiv", "Aktiv", null);
        aktiv.setLastSeenAt(Instant.now().minusSeconds(30));
        inaktiv = AppUser.create("aaa.inaktiv", "Inaktiv", null);
        inaktiv.setLastSeenAt(Instant.now().minus(Duration.ofHours(3)));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(aktiv, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void zeigtWerAngemeldetIstUndSortiertNachBenutzername() {
        when(userRepo.findAll()).thenReturn(List.of(aktiv, inaktiv));
        when(recordingRepo.findByStatusIn(anyList())).thenReturn(List.of());

        var users = controller.listUsers();

        assertThat(users).extracting(Dtos.AdminUserView::username)
                .containsExactly("aaa.inaktiv", "zzz.aktiv");
        assertThat(users).extracting(Dtos.AdminUserView::online).containsExactly(false, true);
    }

    @Test
    void ordnetLaufendeAufnahmenIhremBesitzerZu() {
        Recording laufend = Recording.start(null, aktiv.getId(), null, "/tmp/a", false, true, false);
        laufend.setSource(Recording.Source.CAPTURE);
        laufend.setTitle("Bildschirm");
        when(userRepo.findAll()).thenReturn(List.of(aktiv, inaktiv));
        when(recordingRepo.findByStatusIn(
                List.of(Recording.Status.RECORDING, Recording.Status.FINALIZING)))
                .thenReturn(List.of(laufend));

        var users = controller.listUsers();

        var mitAufnahme = users.stream().filter(u -> u.username().equals("zzz.aktiv")).findFirst().orElseThrow();
        var ohneAufnahme = users.stream().filter(u -> u.username().equals("aaa.inaktiv")).findFirst().orElseThrow();
        assertThat(mitAufnahme.activeRecordings()).hasSize(1);
        assertThat(mitAufnahme.activeRecordings().get(0).source()).isEqualTo("CAPTURE");
        assertThat(mitAufnahme.activeRecordings().get(0).status()).isEqualTo("RECORDING");
        assertThat(mitAufnahme.activeRecordings().get(0).title()).isEqualTo("Bildschirm");
        assertThat(ohneAufnahme.activeRecordings()).isEmpty();
    }

    @Test
    void bewertetDasOnlineFensterAmRand() {
        assertThat(UserActivityService.isOnline(null)).isFalse();
        assertThat(UserActivityService.isOnline(Instant.now().minusSeconds(10))).isTrue();
        assertThat(UserActivityService.isOnline(
                Instant.now().minus(UserActivityService.ONLINE_WINDOW).minusSeconds(5))).isFalse();
    }

    @Test
    void schreibtDieAktivitaetNurEinmalProIntervall() {
        AppUserRepo repo = mock(AppUserRepo.class);
        UserActivityService activity = new UserActivityService(repo);
        AppUser user = AppUser.create("m.mustermann", "Mustermann", null);

        activity.touch(user);
        activity.touch(user);
        activity.touch(user);

        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(1)).save(user);
        assertThat(user.getLastSeenAt()).isNotNull();
    }
}
