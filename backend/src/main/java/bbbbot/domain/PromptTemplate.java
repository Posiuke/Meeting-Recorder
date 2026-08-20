package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persoenliche Promptvorlage eines Nutzers: ein benannter Auswertungs-Prompt,
 * der im Dialog "Auswertung anpassen" wiederverwendet werden kann - optional mit
 * eigenem Modell und eigener Temperatur.
 *
 * <p>Modell und Temperatur gehoeren zur Vorlage, weil beide zum Prompt gehoeren:
 * Ein knapper Beschluss-Prompt will ein anderes Modell und eine andere
 * Temperatur als eine ausfuehrliche Protokoll-Vorlage. {@code null} heisst
 * "Vorgabe des Admins verwenden" ({@code llm.model}, {@code llm.temperature}).
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

    /** Modell fuer diese Vorlage (null = Admin-Vorgabe {@code llm.model}). */
    @Column(length = 200)
    private String model;

    /** Temperatur fuer diese Vorlage (null = Admin-Vorgabe {@code llm.temperature}). */
    private Double temperature;

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
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
