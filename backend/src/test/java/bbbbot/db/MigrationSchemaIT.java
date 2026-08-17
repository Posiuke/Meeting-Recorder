package bbbbot.db;

import bbbbot.domain.AppUser;
import bbbbot.domain.GlossaryEntry;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.RecordingTag;
import bbbbot.domain.ShareLink;
import bbbbot.repository.Repositories.GlossaryEntryRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.RecordingTagRepo;
import bbbbot.repository.Repositories.ShareLinkRepo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

        List<UUID> hits = tagRepo.findRecordingIdsByNameKeyLike("%nord%");
        assertThat(hits).contains(recording.getId());
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
        recordingRepo.saveAndFlush(recording);
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
