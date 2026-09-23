package bbbbot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persoenliche Bot-Vorlage eines Nutzers: benannter Meetingraum samt der
 * Einstellungen, mit denen der Bot dort aufzeichnen soll. Statt die URL und die
 * Schalter jedes Mal neu einzutragen, bleibt nur noch "Vorlage waehlen, Bot
 * starten" (Issue #27).
 *
 * <p>Die Vorlage gehoert genau einem Nutzer und ist fuer niemanden sonst
 * sichtbar - in der Meeting-URL steckt der Zugang zum Raum.
 *
 * <p>{@code diarize} haelt den Wunsch des Nutzers fest, nicht die Freigabe: Ob
 * die Sprechererkennung wirklich laeuft, entscheidet beim Start die
 * Admin-Einstellung {@code whisper.diarize}. So bleibt die Vorlage brauchbar,
 * wenn der Admin die Funktion spaeter wieder freischaltet.
 *
 * <p>Optional traegt die Vorlage einen Zeitplan (Wochentage, Start- und
 * Endzeit in einer Zeitzone): Der {@link bbbbot.bot.BotScheduler} startet den
 * Bot dann selbst und beendet ihn zur Endzeit. Dazu kommt die
 * Auswertungs-Vorlage, mit der die Aufnahmen im Anschluss ausgewertet werden.
 */
@Entity
@Table(name = "bot_template")
public class BotTemplate {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String meetingUrl;

    @Column(nullable = false, length = 100)
    private String botName;

    @Column(nullable = false)
    private boolean autoRecord;

    @Column(nullable = false)
    private boolean recordVideo;

    @Column(nullable = false)
    private boolean aiAnalysis;

    @Column(nullable = false)
    private boolean diarize;

    /** Sprache der Spracherkennung (null = Admin-Standard, "auto" = selbst erkennen). */
    @Column(length = 16)
    private String sttLanguage;

    /** Zeitplan aktiv: Der Bot tritt an {@link #scheduleDays} selbst bei. */
    @Column(nullable = false)
    private boolean scheduleEnabled;

    /** Komma-getrennte Wochentage ({@link DayOfWeek#name()}). */
    @Column(length = 80)
    private String scheduleDays;

    private LocalTime scheduleStart;

    /** Endzeit; liegt sie vor der Startzeit, endet der Termin am Folgetag. */
    private LocalTime scheduleEnd;

    /** IANA-Zeitzone, in der Start- und Endzeit gelten (z.B. Europe/Berlin). */
    @Column(length = 64)
    private String scheduleTimeZone;

    /**
     * Auswahl der Auswertungs-Vorlage, wie die Oberflaeche sie trifft: null =
     * Admin-Standard, {@code tpl:<id>} = eigene Promptvorlage, sonst Schluessel
     * einer integrierten Vorlage.
     */
    @Column(length = 64)
    private String summaryPreset;

    /** Stand der Auswertungs-Vorlage beim Speichern (siehe V28). */
    @Column(columnDefinition = "text")
    private String summaryPrompt;

    @Column(length = 200)
    private String summaryTemplateName;

    @Column(length = 200)
    private String summaryModel;

    private Double summaryTemperature;

    /**
     * Adresse, unter der der Nutzer die Anwendung beim Speichern aufgerufen hat
     * (Browser-Origin). Daraus baut ein vom Zeitplan gestarteter Bot seinen
     * Stopp-Link - dort gibt es keine Browser-Anfrage.
     */
    @Column(length = 255)
    private String appOrigin;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    public static BotTemplate create(UUID ownerId, String name, String meetingUrl, String botName) {
        BotTemplate t = new BotTemplate();
        t.id = UUID.randomUUID();
        t.ownerId = ownerId;
        t.name = name;
        t.meetingUrl = meetingUrl;
        t.botName = botName;
        t.autoRecord = true;
        t.aiAnalysis = true;
        t.createdAt = Instant.now();
        return t;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMeetingUrl() { return meetingUrl; }
    public void setMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; }
    public String getBotName() { return botName; }
    public void setBotName(String botName) { this.botName = botName; }
    public boolean isAutoRecord() { return autoRecord; }
    public void setAutoRecord(boolean autoRecord) { this.autoRecord = autoRecord; }
    public boolean isRecordVideo() { return recordVideo; }
    public void setRecordVideo(boolean recordVideo) { this.recordVideo = recordVideo; }
    public boolean isAiAnalysis() { return aiAnalysis; }
    public void setAiAnalysis(boolean aiAnalysis) { this.aiAnalysis = aiAnalysis; }
    public boolean isDiarize() { return diarize; }
    public void setDiarize(boolean diarize) { this.diarize = diarize; }
    public String getSttLanguage() { return sttLanguage; }
    public void setSttLanguage(String sttLanguage) { this.sttLanguage = sttLanguage; }
    public boolean isScheduleEnabled() { return scheduleEnabled; }
    public void setScheduleEnabled(boolean scheduleEnabled) { this.scheduleEnabled = scheduleEnabled; }

    /** Wochentage des Zeitplans; leer, wenn keine gesetzt sind. */
    public Set<DayOfWeek> getScheduleDays() {
        if (scheduleDays == null || scheduleDays.isBlank()) return EnumSet.noneOf(DayOfWeek.class);
        return Arrays.stream(scheduleDays.split(","))
                .map(String::trim)
                .filter(d -> !d.isEmpty())
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    public void setScheduleDays(Set<DayOfWeek> days) {
        this.scheduleDays = days == null || days.isEmpty() ? null
                : EnumSet.copyOf(days).stream().map(DayOfWeek::name).collect(Collectors.joining(","));
    }

    public LocalTime getScheduleStart() { return scheduleStart; }
    public void setScheduleStart(LocalTime scheduleStart) { this.scheduleStart = scheduleStart; }
    public LocalTime getScheduleEnd() { return scheduleEnd; }
    public void setScheduleEnd(LocalTime scheduleEnd) { this.scheduleEnd = scheduleEnd; }
    public String getScheduleTimeZone() { return scheduleTimeZone; }
    public void setScheduleTimeZone(String scheduleTimeZone) { this.scheduleTimeZone = scheduleTimeZone; }

    public String getSummaryPreset() { return summaryPreset; }
    public void setSummaryPreset(String summaryPreset) { this.summaryPreset = summaryPreset; }

    /** Gespeicherter Stand der Auswertungs-Vorlage. */
    public SummaryChoice getSummaryChoice() {
        return new SummaryChoice(summaryPrompt, summaryTemplateName, summaryModel, summaryTemperature);
    }

    public void setSummaryChoice(SummaryChoice choice) {
        this.summaryPrompt = choice.prompt();
        this.summaryTemplateName = choice.templateName();
        this.summaryModel = choice.model();
        this.summaryTemperature = choice.temperature();
    }

    public String getAppOrigin() { return appOrigin; }
    public void setAppOrigin(String appOrigin) { this.appOrigin = appOrigin; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
