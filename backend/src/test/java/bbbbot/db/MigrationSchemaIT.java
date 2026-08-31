package bbbbot.db;

import bbbbot.domain.AppUser;
import bbbbot.domain.BotTemplate;
import bbbbot.domain.GlossaryEntry;
import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingDocument;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.RecordingTag;
import bbbbot.domain.ShareLink;
import bbbbot.domain.Summary;
import bbbbot.repository.Repositories.BotTemplateRepo;
import bbbbot.repository.Repositories.GlossaryEntryRepo;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingDocumentRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.RecordingTagRepo;
import bbbbot.repository.Repositories.ShareLinkRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import bbbbot.recording.RecordingFilter;
import bbbbot.recording.RecordingSearch;
import bbbbot.recording.RecordingSort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fuehrt ALLE Flyway-Migrationen gegen eine echte PostgreSQL-Datenbank aus und
 * laesst Hibernate das Ergebnis gegen die Entitaeten pruefen
 * ({@code ddl-auto=validate}). Damit fliegt eine Migration auf, die nicht zum
 * Mapping passt (fehlende Spalte, falscher Typ, Tippfehler) - sonst faellt das
 * erst beim Start auf dem Zielsystem auf.
 *
 * <p>Standardmaessig deaktiviert (wie die Live-Tests unter {@code bbbbot.it}), weil
 * eine Datenbank noetig ist. H2 kommt bewusst nicht in Frage: Die bestehenden
 * Migrationen nutzen Postgres-Syntax ({@code TIMESTAMPTZ}, mehrspaltiges
 * {@code ALTER TABLE}), und deren Pruefsummen duerfen sich nicht mehr aendern.
 *
 * <p><b>Auf einer LEEREN Wegwerf-Datenbank ausfuehren</b> - die Migrationen
 * laufen scharf:
 *
 * <pre>{@code
 * docker exec bbbbot-dev-db psql -U bbbbot -d postgres -c 'CREATE DATABASE bbbbot_migtest'
 * mvn test -Dtest=MigrationSchemaIT \
 *   -Ddb.it.url=jdbc:postgresql://127.0.0.1:5433/bbbbot_migtest \
 *   -Ddb.it.user=bbbbot -Ddb.it.password=...
 * docker exec bbbbot-dev-db psql -U bbbbot -d postgres -c 'DROP DATABASE bbbbot_migtest'
 * }</pre>
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${db.it.url}",
        "spring.datasource.username=${db.it.user:bbbbot}",
        "spring.datasource.password=${db.it.password:bbbbot}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "db.it.url", matches = ".+")
class MigrationSchemaIT {

    @Autowired
    private RecordingRepo recordingRepo;

    @Autowired
    private RecordingTagRepo tagRepo;

    @Autowired
    private RecordingSegmentRepo segmentRepo;

    @Autowired
    private GlossaryEntryRepo glossaryRepo;

    @Autowired
    private ShareLinkRepo shareLinkRepo;

    @Autowired
    private SummaryRepo summaryRepo;

    @Autowired
    private RecordingDocumentRepo documentRepo;

    @Autowired
    private BotTemplateRepo botTemplateRepo;

    @Autowired
    private ProcessingJobRepo jobRepo;

    @Autowired
    private EntityManager em;

    @Test
    void schemaPasstZuDenEntitaeten() {
        // Kommt der Kontext hoch, haben Flyway und die Hibernate-Validierung
        // bereits zugestimmt. Schreiben und Lesen prueft zusaetzlich die neuen
        // Spalten (source, capture_last_chunk_at) und die Schlagwort-Tabelle.
        Recording recording = Recording.start(null, ownerId(), null, "/tmp/x", false, true, false);
        recording.setSource(Recording.Source.CAPTURE);
        recording.setCaptureLastChunkAt(java.time.Instant.now());
        recording.setTitle("Wochenbesprechung");
        recordingRepo.saveAndFlush(recording);

        tagRepo.saveAndFlush(RecordingTag.create(recording.getId(), "Projekt Nord"));

        assertThat(recordingRepo.findBySourceAndStatus(
                Recording.Source.CAPTURE, Recording.Status.RECORDING))
                .extracting(Recording::getId)
                .contains(recording.getId());

        // Die Suchabfrage der Aufnahmenliste gegen Postgres: Schlagwort-Treffer.
        // Sie steckt in RecordingSearch und wird sonst nur gegen H2 geprueft.
        assertThat(searchIds("nord", false)).contains(recording.getId());
        assertThat(tagRepo.findByRecordingIdOrderByNameKeyAsc(recording.getId()))
                .extracting(RecordingTag::getName)
                .containsExactly("Projekt Nord");

        // Geglaettetes Transkript (V15) und persoenliches Glossar
        RecordingSegment segment = RecordingSegment.create(recording.getId(), 0, "/tmp/x.webm");
        segment.setStatus(RecordingSegment.Status.READY);
        segment.setTranscriptText("[00:01] ähm also der rz termin");
        segment.setCorrectedText("[00:01] Der RZ-Termin.");
        segmentRepo.saveAndFlush(segment);
        assertThat(segmentRepo.findByRecordingIdOrderBySeq(recording.getId()))
                .singleElement()
                .satisfies(s -> assertThat(s.getEffectiveTranscript()).isEqualTo("[00:01] Der RZ-Termin."));

        glossaryRepo.saveAndFlush(GlossaryEntry.create(
                recording.getOwnerId(), "RZ", "Rechenzentrum"));
        assertThat(glossaryRepo.findByOwnerIdAndTermKey(recording.getOwnerId(), "rz")).isPresent();

        // Gemeinsames Glossar der Installation (V22): derselbe Begriff darf
        // zusaetzlich ohne Besitzer stehen - die Teil-Indexe trennen die Listen.
        glossaryRepo.saveAndFlush(GlossaryEntry.create(null, "RZ", "Rechenzentrum Nord"));
        assertThat(glossaryRepo.findByOwnerIdIsNullAndTermKey("rz"))
                .get()
                .satisfies(e -> assertThat(e.isShared()).isTrue());
        assertThat(glossaryRepo.findByOwnerIdIsNullOrderByTermKeyAsc()).hasSize(1);
        assertThat(glossaryRepo.countByOwnerIdIsNull()).isEqualTo(1);

        // Beigefuegte Unterlage (V25): Datei-Metadaten und extrahierter Text
        RecordingDocument document = RecordingDocument.create(recording.getId(),
                "tagesordnung.md", "text/markdown", recording.getOwnerId());
        document.setStoredPath("/tmp/x/documents/tagesordnung.md");
        document.setSizeBytes(1234);
        document.setExtractedText("1. Projekt Nord");
        document.setTextChars(15);
        document.setStatus(RecordingDocument.Status.READY);
        document.setExtractedAt(java.time.Instant.now());
        documentRepo.saveAndFlush(document);
        assertThat(documentRepo.findByRecordingIdOrderByCreatedAtAsc(recording.getId()))
                .singleElement()
                .satisfies(d -> assertThat(d.isUsable()).isTrue())
                .satisfies(d -> assertThat(d.extension()).isEqualTo("md"));
        assertThat(documentRepo.findByStatus(RecordingDocument.Status.PENDING)).isEmpty();
        assertThat(documentRepo.countByRecordingId(recording.getId())).isEqualTo(1);

        // Oeffentlicher Freigabe-Link (V18)
        ShareLink link = ShareLink.create(recording.getId(), "token-" + UUID.randomUUID(),
                recording.getOwnerId(), null, true);
        shareLinkRepo.saveAndFlush(link);
        assertThat(shareLinkRepo.findByToken(link.getToken()))
                .get()
                .satisfies(l -> assertThat(l.getViews()).isZero())
                .satisfies(l -> assertThat(l.isRequireLogin()).isTrue());
        assertThat(shareLinkRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId()))
                .hasSize(1);

        recording.setCorrectionStatus(Recording.CorrectionStatus.READY);
        recording.setSummaryTemplateName("Meeting");
        // Modell und Temperatur je Aufnahme (V24)
        recording.setSummaryModel("vergleichs-modell");
        recording.setSummaryTemperature(0.9);
        recordingRepo.saveAndFlush(recording);

        // Fassungen der Zusammenfassung (V23): zwei Fassungen, genau eine aktuell.
        Summary alt = summary(recording.getId(), "# Alte Fassung\n", false);
        Summary aktuell = summary(recording.getId(), "# Aktuelle Fassung\n", true);
        assertThat(summaryRepo.findByRecordingIdAndCurrentIsTrue(recording.getId()))
                .get()
                .satisfies(sum -> assertThat(sum.getId()).isEqualTo(aktuell.getId()))
                .satisfies(sum -> assertThat(sum.getTemplateName()).isEqualTo("Meeting"))
                .satisfies(sum -> assertThat(sum.getTemperature()).isEqualTo(0.9));
        assertThat(summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId())).hasSize(2);
        // Die Suche in Zusammenfassungen findet nur die aktuelle Fassung
        assertThat(searchIds("aktuelle fassung", true)).contains(recording.getId());
        assertThat(searchIds("alte fassung", true)).isEmpty();

        // Der Teil-Index uq_summary_current laesst nur eine aktuelle Fassung zu
        alt.setCurrent(true);
        assertThatThrownBy(() -> summaryRepo.saveAndFlush(alt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Kennungen der Aufnahmen, die die Listensuche zu diesem Begriff findet -
     * hier gegen echtes PostgreSQL statt gegen H2 wie im Unit-Test.
     */
    private List<UUID> searchIds(String text, boolean content) {
        return new RecordingSearch(em)
                .search(ownerOfEverything(), RecordingFilter.of(text, null, content,
                        null, null, null, null), RecordingSort.DEFAULT, Pageable.unpaged())
                .getContent().stream()
                .map(Recording::getId)
                .toList();
    }

    /**
     * Der Nutzer, dem die Aufnahmen dieses Tests gehoeren. Die Suche filtert nach
     * Sichtbarkeit; ohne den richtigen Besitzer kaeme nichts zurueck.
     */
    private UUID ownerOfEverything() {
        return recordingRepo.findAll().stream()
                .map(Recording::getOwnerId)
                .findFirst()
                .orElseThrow();
    }

    /** Fertige Fassung mit Inhalt, direkt gespeichert. */
    private Summary summary(UUID recordingId, String markdown, boolean current) {
        Summary summary = Summary.create(recordingId);
        summary.setStatus(Summary.Status.DONE);
        summary.setMarkdown(markdown);
        summary.setSystemPrompt("Fasse die Aufnahme zusammen.");
        summary.setTemplateName("Meeting");
        summary.setModel("vergleichs-modell");
        summary.setTemperature(0.9);
        summary.setCurrent(current);
        return summaryRepo.saveAndFlush(summary);
    }

    /**
     * Verarbeitungs-Auftraege (V27): Die Schrittdauern lassen sich schreiben und
     * lesen, und die Abfragen der Admin-Uebersicht treffen die richtigen Zeilen.
     */
    @Test
    void schrittdauernUndWarteschlangenAbfragen() {
        Recording recording = Recording.start(null, ownerId(), null, "/tmp/x", false, true, false);
        recordingRepo.saveAndFlush(recording);

        ProcessingJob fertig = ProcessingJob.create(recording.getId(), false);
        fertig.setStatus(ProcessingJob.Status.DONE);
        fertig.setStartedAt(java.time.Instant.now().minusSeconds(300));
        fertig.setFinishedAt(java.time.Instant.now());
        fertig.setSttMs(240_000L);
        fertig.setCorrectionMs(30_000L);
        fertig.setSummaryMs(25_000L);
        jobRepo.saveAndFlush(fertig);

        ProcessingJob wartend = ProcessingJob.create(recording.getId(), true);
        jobRepo.saveAndFlush(wartend);

        ProcessingJob gescheitert = ProcessingJob.create(recording.getId(), false);
        gescheitert.setStatus(ProcessingJob.Status.FAILED);
        gescheitert.setFinishedAt(java.time.Instant.now());
        gescheitert.setLastError("Whisper nicht erreichbar");
        jobRepo.saveAndFlush(gescheitert);

        assertThat(jobRepo.findTop50ByStatusOrderByFinishedAtDesc(ProcessingJob.Status.DONE))
                .singleElement()
                .satisfies(j -> assertThat(j.getSttMs()).isEqualTo(240_000L))
                .satisfies(j -> assertThat(j.getCorrectionMs()).isEqualTo(30_000L))
                .satisfies(j -> assertThat(j.getSummaryMs()).isEqualTo(25_000L))
                .satisfies(j -> assertThat(j.durationMs()).isNotNull());

        assertThat(jobRepo.findByStatusInOrderByCreatedAtAsc(
                List.of(ProcessingJob.Status.PENDING, ProcessingJob.Status.RUNNING)))
                .extracting(ProcessingJob::getId)
                .containsExactly(wartend.getId());

        assertThat(jobRepo.findTop20ByStatusOrderByFinishedAtDesc(ProcessingJob.Status.FAILED))
                .singleElement()
                .satisfies(j -> assertThat(j.getLastError()).isEqualTo("Whisper nicht erreichbar"));

        assertThat(jobRepo.countByStatus(ProcessingJob.Status.PENDING)).isEqualTo(1);
        assertThat(jobRepo.countByStatus(ProcessingJob.Status.FAILED)).isEqualTo(1);
    }

    /**
     * Bot-Vorlagen (V26): Die Vorlagen zweier Nutzer duerfen denselben Namen
     * tragen, ein Nutzer denselben Namen aber nur einmal - genau das sichert
     * uq_bot_template_owner_name (case-insensitive) zu.
     */
    @Test
    void botVorlagenSindProNutzerEindeutig() {
        UUID owner = ownerId();
        BotTemplate vorlage = BotTemplate.create(owner, "Technikrunde",
                "https://bbb.example.org/b/abc-def-ghi", "Protokoll-Bot");
        vorlage.setRecordVideo(true);
        vorlage.setDiarize(true);
        vorlage.setSttLanguage("de");
        botTemplateRepo.saveAndFlush(vorlage);

        assertThat(botTemplateRepo.findByOwnerIdOrderByNameAsc(owner))
                .singleElement()
                .satisfies(t -> assertThat(t.getMeetingUrl()).isEqualTo("https://bbb.example.org/b/abc-def-ghi"))
                .satisfies(t -> assertThat(t.getBotName()).isEqualTo("Protokoll-Bot"))
                .satisfies(t -> assertThat(t.isAutoRecord()).isTrue())
                .satisfies(t -> assertThat(t.isRecordVideo()).isTrue())
                .satisfies(t -> assertThat(t.isDiarize()).isTrue())
                .satisfies(t -> assertThat(t.getSttLanguage()).isEqualTo("de"));
        assertThat(botTemplateRepo.existsByOwnerIdAndNameIgnoreCase(owner, "technikrunde")).isTrue();
        assertThat(botTemplateRepo.countByOwnerId(owner)).isEqualTo(1);

        // Anderer Nutzer, gleicher Name: erlaubt.
        botTemplateRepo.saveAndFlush(BotTemplate.create(ownerId(), "Technikrunde",
                "https://bbb.example.org/b/abc-def-ghi", "RecorderBot"));

        // Derselbe Nutzer, gleicher Name in anderer Schreibweise: abgewiesen.
        BotTemplate doppelt = BotTemplate.create(owner, "technikRUNDE",
                "https://bbb.example.org/b/xyz", "RecorderBot");
        assertThatThrownBy(() -> botTemplateRepo.saveAndFlush(doppelt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Das gezielte Video-Update fasst wirklich nur die Video-Spalten an - und
     * ueberholt dabei eine laengst veraltete Kopie derselben Zeile.
     *
     * <p>Genau das war die Ursache haengender Videos: Der Verarbeitungs-Job haelt
     * die Aufnahme ueber seine ganze Laufzeit im Speicher und schrieb sie am Ende
     * komplett zurueck - inklusive des alten Video-Status.
     */
    @Test
    void videoUpdateSchreibtNurDieVideoSpalten() {
        Recording recording = Recording.start(null, ownerId(), null, "/tmp/x", true, true, false);
        recording.setVideoStatus(Recording.VideoStatus.MUXING);
        recording.setStatus(Recording.Status.PROCESSING);
        recordingRepo.saveAndFlush(recording);
        em.clear();

        recordingRepo.updateVideoState(recording.getId(), Recording.VideoStatus.READY, "/tmp/x/meeting.mp4");
        em.clear();

        assertThat(recordingRepo.findById(recording.getId()))
                .get()
                .satisfies(r -> assertThat(r.getVideoStatus()).isEqualTo(Recording.VideoStatus.READY))
                .satisfies(r -> assertThat(r.getVideoPath()).isEqualTo("/tmp/x/meeting.mp4"))
                // Der Status der Aufnahme darf dabei unangetastet bleiben.
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(Recording.Status.PROCESSING));

        assertThat(recordingRepo.findByVideoStatusIn(List.of(Recording.VideoStatus.MUXING)))
                .extracting(Recording::getId)
                .doesNotContain(recording.getId());
    }

    /** Aufnahmen verweisen per Fremdschluessel auf app_user - Nutzer also zuerst anlegen. */
    private UUID ownerId() {
        AppUser user = AppUser.create("tester-" + UUID.randomUUID(), "Tester", null);
        em.persist(user);
        em.flush();
        return user.getId();
    }
}
