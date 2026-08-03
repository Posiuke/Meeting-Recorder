package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_grant")
public class ShareGrant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID recordingId;

    private UUID granteeUserId;

    private UUID granteeGroupId;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    public static ShareGrant forUser(UUID recordingId, UUID userId, UUID createdBy) {
        ShareGrant s = base(recordingId, createdBy);
        s.granteeUserId = userId;
        return s;
    }

    public static ShareGrant forGroup(UUID recordingId, UUID groupId, UUID createdBy) {
        ShareGrant s = base(recordingId, createdBy);
        s.granteeGroupId = groupId;
        return s;
    }

    private static ShareGrant base(UUID recordingId, UUID createdBy) {
        ShareGrant s = new ShareGrant();
        s.id = UUID.randomUUID();
        s.recordingId = recordingId;
        s.createdBy = createdBy;
        s.createdAt = Instant.now();
        return s;
    }

    public UUID getId() { return id; }
    public UUID getRecordingId() { return recordingId; }
    public UUID getGranteeUserId() { return granteeUserId; }
    public UUID getGranteeGroupId() { return granteeGroupId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
