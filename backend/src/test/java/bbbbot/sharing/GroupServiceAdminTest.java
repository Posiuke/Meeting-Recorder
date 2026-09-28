package bbbbot.sharing;

import bbbbot.domain.AppUser;
import bbbbot.domain.GroupMember;
import bbbbot.domain.UserGroup;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.GroupMemberRepo;
import bbbbot.repository.Repositories.UserGroupRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admins verwalten fremde Gruppen: umbenennen, Besitzer wechseln, loeschen. */
class GroupServiceAdminTest {

    private UserGroupRepo groupRepo;
    private GroupMemberRepo memberRepo;
    private AppUserRepo userRepo;
    private GroupService service;

    private AppUser owner;
    private AppUser other;
    private AppUser admin;
    private UserGroup group;

    @BeforeEach
    void setup() {
        groupRepo = mock(UserGroupRepo.class);
        memberRepo = mock(GroupMemberRepo.class);
        userRepo = mock(AppUserRepo.class);
        service = new GroupService(groupRepo, memberRepo, userRepo);

        owner = AppUser.create("owner", "Owner", "o@x");
        other = AppUser.create("other", "Other", "e@x");
        admin = AppUser.create("admin", "Admin", "a@x");
        admin.setAdmin(true);
        group = UserGroup.create("Vertrieb", owner.getId());
        when(groupRepo.findById(group.getId())).thenReturn(Optional.of(group));
        when(groupRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepo.findById(other.getId())).thenReturn(Optional.of(other));
        when(userRepo.existsById(owner.getId())).thenReturn(true);
        when(memberRepo.findByGroupIdAndUserId(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void adminDarfFremdeGruppeLoeschen() {
        service.delete(group.getId(), admin);

        verify(groupRepo).delete(group);
    }

    @Test
    void fremderDarfGruppeNichtLoeschen() {
        assertThatThrownBy(() -> service.delete(group.getId(), other))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verify(groupRepo, never()).delete(any());
    }

    @Test
    void adminBenenntUm() {
        UserGroup renamed = service.adminUpdate(group.getId(), "  Vertrieb Nord ", null, admin);

        assertThat(renamed.getName()).isEqualTo("Vertrieb Nord");
    }

    @Test
    void umbenennenAufVergebenenNamenErgibt409() {
        when(groupRepo.existsByNameIgnoreCase("Einkauf")).thenReturn(true);

        assertThatThrownBy(() -> service.adminUpdate(group.getId(), "Einkauf", null, admin))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    /** Nur Gross-/Kleinschreibung geaendert: kein Konflikt mit sich selbst. */
    @Test
    void umbenennenInEigenerSchreibweiseIstErlaubt() {
        when(groupRepo.existsByNameIgnoreCase("VERTRIEB")).thenReturn(true);

        assertThat(service.adminUpdate(group.getId(), "VERTRIEB", null, admin).getName())
                .isEqualTo("VERTRIEB");
    }

    @Test
    void besitzerwechselBehaeltAltenBesitzerAlsMitglied() {
        UserGroup changed = service.adminUpdate(group.getId(), null, other.getId(), admin);

        assertThat(changed.getOwnerId()).isEqualTo(other.getId());
        verify(memberRepo).deleteByGroupIdAndUserId(group.getId(), other.getId());
        ArgumentCaptor<GroupMember> added = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberRepo).save(added.capture());
        assertThat(added.getValue().getUserId()).isEqualTo(owner.getId());
    }

    @Test
    void besitzerwechselNurFuerAdmins() {
        assertThatThrownBy(() -> service.adminUpdate(group.getId(), null, other.getId(), owner))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThat(group.getOwnerId()).isEqualTo(owner.getId());
    }
}
