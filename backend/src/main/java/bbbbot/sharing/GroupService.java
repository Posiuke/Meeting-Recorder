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

    /** Alle Gruppen - nur fuer die Admin-Verwaltung. */
    public List<UserGroup> listAll() {
        return groupRepo.findAll();
    }

    @Transactional
    public UserGroup create(String name, AppUser owner) {
        return groupRepo.save(UserGroup.create(requireFreeName(name, null), owner.getId()));
    }

    /** Umbenennen - durch den Besitzer oder einen Admin. */
    @Transactional
    public UserGroup rename(UUID groupId, String name, AppUser actor) {
        UserGroup group = requireGroup(groupId);
        requireOwner(group, actor);
        group.setName(requireFreeName(name, group));
        return groupRepo.save(group);
    }

    /**
     * Besitz uebertragen - nur Admins, z.B. wenn der Besitzer das Haus
     * verlassen hat. Der bisherige Besitzer bleibt als Mitglied in der Gruppe,
     * damit er die freigegebenen Aufnahmen nicht stillschweigend verliert; der
     * neue wird als Mitglied ausgetragen, weil er als Besitzer ohnehin alles sieht.
     */
    @Transactional
    public UserGroup changeOwner(UUID groupId, UUID newOwnerId, AppUser actor) {
        if (!actor.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur Admins duerfen den Besitzer aendern");
        }
        UserGroup group = requireGroup(groupId);
        AppUser target = userRepo.findById(newOwnerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nutzer nicht gefunden"));
        UUID previous = group.getOwnerId();
        if (previous.equals(target.getId())) return group;
        group.setOwnerId(target.getId());
        memberRepo.deleteByGroupIdAndUserId(groupId, target.getId());
        if (memberRepo.findByGroupIdAndUserId(groupId, previous).isEmpty()
                && userRepo.existsById(previous)) {
            memberRepo.save(GroupMember.create(groupId, previous));
        }
        return groupRepo.save(group);
    }

    /**
     * Aenderung durch einen Admin in einem Zug: Name und/oder Besitzer
     * (null = bleibt). Scheitert ein Teil, bleibt auch der andere unveraendert.
     */
    @Transactional
    public UserGroup adminUpdate(UUID groupId, String name, UUID ownerId, AppUser actor) {
        UserGroup group = requireGroup(groupId);
        if (name != null) group = rename(groupId, name, actor);
        if (ownerId != null) group = changeOwner(groupId, ownerId, actor);
        return group;
    }

    private String requireFreeName(String name, UserGroup self) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gruppenname darf nicht leer sein");
        }
        boolean unchanged = self != null && self.getName().equalsIgnoreCase(trimmed);
        if (!unchanged && groupRepo.existsByNameIgnoreCase(trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Gruppenname bereits vergeben");
        }
        return trimmed;
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
