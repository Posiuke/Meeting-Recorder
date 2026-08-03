package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_member")
public class GroupMember {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID groupId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private Instant addedAt;

    public static GroupMember create(UUID groupId, UUID userId) {
        GroupMember m = new GroupMember();
        m.id = UUID.randomUUID();
        m.groupId = groupId;
        m.userId = userId;
        m.addedAt = Instant.now();
        return m;
    }

    public UUID getId() { return id; }
    public UUID getGroupId() { return groupId; }
    public UUID getUserId() { return userId; }
    public Instant getAddedAt() { return addedAt; }
}
