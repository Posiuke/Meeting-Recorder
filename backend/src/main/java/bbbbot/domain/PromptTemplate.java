package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persoenliche Promptvorlage eines Nutzers: ein benannter Auswertungs-Prompt,
 * der im Dialog "Auswertung anpassen" wiederverwendet werden kann.
 */
@Entity
@Table(name = "prompt_template")
public class PromptTemplate {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    public static PromptTemplate create(UUID ownerId, String name, String prompt) {
        PromptTemplate t = new PromptTemplate();
        t.id = UUID.randomUUID();
        t.ownerId = ownerId;
        t.name = name;
        t.prompt = prompt;
        t.createdAt = Instant.now();
        return t;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
