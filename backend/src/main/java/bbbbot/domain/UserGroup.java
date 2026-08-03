package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_group")
public class UserGroup {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private Instant createdAt;

    public static UserGroup create(String name, UUID ownerId) {
        UserGroup g = new UserGroup();
        g.id = UUID.randomUUID();
        g.name = name;
        g.ownerId = ownerId;
        g.createdAt = Instant.now();
        return g;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getOwnerId() { return ownerId; }
    public Instant getCreatedAt() { return createdAt; }
}
