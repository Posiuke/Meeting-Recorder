package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.domain.UserGroup;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.GroupMemberRepo;
import bbbbot.repository.Repositories.ShareGrantRepo;
import bbbbot.sharing.GroupService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gruppenverwaltung fuer Admins: alle Gruppen sehen - auch die, in denen der
 * Admin weder Besitzer noch Mitglied ist -, umbenennen, den Besitzer wechseln
 * und loeschen. Mitglieder verwaltet der Admin ueber die normalen
 * Gruppen-Endpunkte, die Admins schon zulassen.
 *
 * <p>Nur fuer Admins: {@code /api/admin/**} ist in der SecurityConfig gesperrt.
 */
@RestController
@RequestMapping("/api/admin/groups")
public class AdminGroupController {

    private final GroupService groupService;
    private final GroupMemberRepo memberRepo;
    private final ShareGrantRepo shareRepo;
    private final AppUserRepo userRepo;

    public AdminGroupController(GroupService groupService, GroupMemberRepo memberRepo,
                                ShareGrantRepo shareRepo, AppUserRepo userRepo) {
        this.groupService = groupService;
        this.memberRepo = memberRepo;
        this.shareRepo = shareRepo;
        this.userRepo = userRepo;
    }

    @GetMapping
    public List<Dtos.AdminGroupView> list() {
        List<UserGroup> groups = groupService.listAll();
        Map<UUID, Long> members = counts(memberRepo.countByGroup());
        Map<UUID, Long> shares = counts(shareRepo.countByGroup());
        Map<UUID, AppUser> owners = userRepo.findAllById(
                        groups.stream().map(UserGroup::getOwnerId).distinct().toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return groups.stream()
                .sorted(Comparator.comparing(UserGroup::getName, String.CASE_INSENSITIVE_ORDER))
                .map(g -> view(g, owners.get(g.getOwnerId()),
                        members.getOrDefault(g.getId(), 0L), shares.getOrDefault(g.getId(), 0L)))
                .toList();
    }

    @PutMapping("/{groupId}")
    public Dtos.AdminGroupView update(@PathVariable UUID groupId,
                                      @RequestBody Dtos.AdminGroupUpdateRequest request) {
        return view(groupService.adminUpdate(groupId, request.name(), request.ownerId(),
                CurrentUser.get()));
    }

    @DeleteMapping("/{groupId}")
    public void delete(@PathVariable UUID groupId) {
        groupService.delete(groupId, CurrentUser.get());
    }

    private Dtos.AdminGroupView view(UserGroup group) {
        Map<UUID, Long> members = counts(memberRepo.countByGroup());
        Map<UUID, Long> shares = counts(shareRepo.countByGroup());
        return view(group, userRepo.findById(group.getOwnerId()).orElse(null),
                members.getOrDefault(group.getId(), 0L), shares.getOrDefault(group.getId(), 0L));
    }

    private static Dtos.AdminGroupView view(UserGroup g, AppUser owner, long memberCount, long shareCount) {
        return new Dtos.AdminGroupView(g.getId(), g.getName(), g.getOwnerId(),
                owner == null ? null : owner.getUsername(),
                owner == null ? null : owner.getDisplayName(),
                memberCount, shareCount, g.getCreatedAt());
    }

    private static Map<UUID, Long> counts(List<Object[]> rows) {
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : rows) result.put((UUID) row[0], ((Number) row[1]).longValue());
        return result;
    }
}
