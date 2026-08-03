package bbbbot.sharing;

import bbbbot.domain.AppUser;
import bbbbot.domain.GroupMember;
import bbbbot.domain.UserGroup;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.GroupMemberRepo;
import bbbbot.repository.Repositories.UserGroupRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** App-eigene Gruppen: jeder Nutzer kann Gruppen erstellen und Mitglieder einladen. */
@Service
public class GroupService {

    private final UserGroupRepo groupRepo;
    private final GroupMemberRepo memberRepo;
    private final AppUserRepo userRepo;

    public GroupService(UserGroupRepo groupRepo, GroupMemberRepo memberRepo, AppUserRepo userRepo) {
        this.groupRepo = groupRepo;
        this.memberRepo = memberRepo;
        this.userRepo = userRepo;
    }

    public List<UserGroup> listVisible(AppUser user) {
        return groupRepo.findAllVisibleTo(user.getId());
    }

    @Transactional
    public UserGroup create(String name, AppUser owner) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gruppenname darf nicht leer sein");
        }
        if (groupRepo.existsByNameIgnoreCase(trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Gruppenname bereits vergeben");
        }
        return groupRepo.save(UserGroup.create(trimmed, owner.getId()));
    }

    @Transactional
    public void delete(UUID groupId, AppUser user) {
        UserGroup group = requireGroup(groupId);
        requireOwner(group, user);
        groupRepo.delete(group);
    }

    public List<GroupMember> members(UUID groupId, AppUser user) {
        UserGroup group = requireGroup(groupId);
        if (!group.getOwnerId().equals(user.getId())
                && memberRepo.findByGroupIdAndUserId(groupId, user.getId()).isEmpty()
                && !user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Gruppe");
        }
        return memberRepo.findByGroupId(groupId);
    }

    @Transactional
    public GroupMember addMember(UUID groupId, UUID userId, AppUser actor) {
        UserGroup group = requireGroup(groupId);
        requireOwner(group, actor);
        AppUser target = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nutzer nicht gefunden"));
        if (memberRepo.findByGroupIdAndUserId(groupId, target.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nutzer ist bereits Mitglied");
        }
        return memberRepo.save(GroupMember.create(groupId, target.getId()));
    }

    @Transactional
    public void removeMember(UUID groupId, UUID userId, AppUser actor) {
        UserGroup group = requireGroup(groupId);
        boolean self = actor.getId().equals(userId);
        if (!self) requireOwner(group, actor);
        memberRepo.deleteByGroupIdAndUserId(groupId, userId);
    }

    public UserGroup requireGroup(UUID groupId) {
        return groupRepo.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden"));
    }

    private void requireOwner(UserGroup group, AppUser user) {
        if (!group.getOwnerId().equals(user.getId()) && !user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur der Gruppen-Besitzer darf das");
        }
    }
}
