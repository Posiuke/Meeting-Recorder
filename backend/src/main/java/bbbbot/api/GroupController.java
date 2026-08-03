package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.sharing.GroupService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;
    private final AppUserRepo userRepo;

    public GroupController(GroupService groupService, AppUserRepo userRepo) {
        this.groupService = groupService;
        this.userRepo = userRepo;
    }

    @GetMapping
    public List<Dtos.GroupView> list() {
        AppUser user = CurrentUser.get();
        return groupService.listVisible(user).stream()
                .map(g -> Dtos.GroupView.of(g, user.getId()))
                .toList();
    }

    @PostMapping
    public Dtos.GroupView create(@RequestBody Dtos.CreateGroupRequest request) {
        AppUser user = CurrentUser.get();
        return Dtos.GroupView.of(groupService.create(request.name(), user), user.getId());
    }

    @DeleteMapping("/{groupId}")
    public void delete(@PathVariable UUID groupId) {
        groupService.delete(groupId, CurrentUser.get());
    }

    @GetMapping("/{groupId}/members")
    public List<Dtos.GroupMemberView> members(@PathVariable UUID groupId) {
        AppUser user = CurrentUser.get();
        return groupService.members(groupId, user).stream()
                .map(m -> {
                    AppUser member = userRepo.findById(m.getUserId()).orElse(null);
                    return new Dtos.GroupMemberView(m.getUserId(),
                            member == null ? "?" : member.getUsername(),
                            member == null ? "?" : member.getDisplayName(),
                            m.getAddedAt());
                })
                .toList();
    }

    @PostMapping("/{groupId}/members")
    public void addMember(@PathVariable UUID groupId, @RequestBody Dtos.AddMemberRequest request) {
        groupService.addMember(groupId, request.userId(), CurrentUser.get());
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public void removeMember(@PathVariable UUID groupId, @PathVariable UUID userId) {
        groupService.removeMember(groupId, userId, CurrentUser.get());
    }
}
