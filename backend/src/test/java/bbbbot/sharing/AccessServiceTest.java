package bbbbot.sharing;

import bbbbot.domain.AppUser;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.ShareGrantRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessServiceTest {

    private RecordingRepo recordingRepo;
    private ShareGrantRepo shareRepo;
    private AccessService access;

    private AppUser owner;
    private AppUser other;
    private AppUser admin;
    private Recording recording;

    @BeforeEach
    void setup() {
        recordingRepo = mock(RecordingRepo.class);
        shareRepo = mock(ShareGrantRepo.class);
        access = new AccessService(recordingRepo, shareRepo);

        owner = AppUser.create("owner", "Owner", "o@x");
        other = AppUser.create("other", "Other", "e@x");
        admin = AppUser.create("admin", "Admin", "a@x");
        admin.setAdmin(true);

        recording = Recording.start(null, owner.getId(), null, "/tmp/x", false, false, false);
        when(recordingRepo.findById(recording.getId())).thenReturn(Optional.of(recording));
    }

    @Test
    void besitzerDarfLesen() {
        assertThat(access.canRead(recording, owner)).isTrue();
    }

    @Test
    void adminDarfImmerLesen() {
        assertThat(access.canRead(recording, admin)).isTrue();
    }

    @Test
    void fremderOhneFreigabeDarfNichtLesen() {
        when(shareRepo.hasAccess(recording.getId(), other.getId())).thenReturn(false);
        assertThat(access.canRead(recording, other)).isFalse();
    }

    @Test
    void fremderMitFreigabeDarfLesen() {
        when(shareRepo.hasAccess(recording.getId(), other.getId())).thenReturn(true);
        assertThat(access.canRead(recording, other)).isTrue();
    }

    @Test
    void requireReadableWirftFuerFremdenOhneFreigabe() {
        when(shareRepo.hasAccess(recording.getId(), other.getId())).thenReturn(false);
        assertThatThrownBy(() -> access.requireReadable(recording.getId(), other))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void requireOwnerErlaubtNurBesitzerUndAdmin() {
        assertThat(access.requireOwner(recording.getId(), owner)).isSameAs(recording);
        assertThat(access.requireOwner(recording.getId(), admin)).isSameAs(recording);
        assertThatThrownBy(() -> access.requireOwner(recording.getId(), other))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void requireOwnerWirft404WennNichtVorhanden() {
        UUID missing = UUID.randomUUID();
        when(recordingRepo.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.requireOwner(missing, owner))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
