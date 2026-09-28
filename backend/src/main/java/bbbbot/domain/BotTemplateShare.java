package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Freigabe einer Bot-Vorlage an einen Nutzer oder eine Gruppe. */
@Entity
@Table(name = "bot_template_share")
public class BotTemplateShare {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID botTemplateId;

    private UUID granteeUserId;

    private UUID granteeGroupId;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    public static BotTemplateShare forUser(UUID templateId, UUID userId, UUID createdBy) {
        BotTemplateShare s = base(templateId, createdBy);
        s.granteeUserId = userId;
        return s;
    }

    public static BotTemplateShare forGroup(UUID templateId, UUID groupId, UUID createdBy) {
        BotTemplateShare s = base(templateId, createdBy);
        s.granteeGroupId = groupId;
        return s;
    }

    private static BotTemplateShare base(UUID templateId, UUID createdBy) {
        BotTemplateShare s = new BotTemplateShare();
        s.id = UUID.randomUUID();
        s.botTemplateId = templateId;
        s.createdBy = createdBy;
        s.createdAt = Instant.now();
        return s;
    }

    public UUID getId() { return id; }
    public UUID getBotTemplateId() { return botTemplateId; }
    public UUID getGranteeUserId() { return granteeUserId; }
    public UUID getGranteeGroupId() { return granteeGroupId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
