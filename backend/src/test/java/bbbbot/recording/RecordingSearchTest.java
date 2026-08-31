package bbbbot.recording;

import bbbbot.domain.GroupMember;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.RecordingTag;
import bbbbot.domain.ShareGrant;
import bbbbot.domain.Summary;
import bbbbot.domain.UserGroup;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Aufnahmensuche gegen eine echte Datenbank (H2 im Speicher, Schema aus den
 * Entitaeten).
 *
 * <p>Bewusst nicht mehr mit Mocks: Berechtigung, Filter, Sortierung und
 * Seitenschnitt stecken jetzt in einer Abfrage. Ein Mock wuerde nur bestaetigen,
 * dass eine Specification gebaut wurde - nicht, dass sie das Richtige findet.
 * H2 genuegt hier, weil ausschliesslich Standard-SQL im Spiel ist (die
 * Postgres-Syntax der Migrationen prueft {@code MigrationSchemaIT}).
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
})
class RecordingSearchTest {

    @Autowired
    private EntityManager em;

    private RecordingSearch search;

    private final UUID ich = UUID.randomUUID();
    private final UUID kollege = UUID.randomUUID();
    private final UUID fremder = UUID.randomUUID();

    /** Zeitachse der Testdaten - feste Abstaende, damit Zeitraum und Sortierung pruefbar sind. */
    private static final Instant JETZT = Instant.parse("2026-08-31T10:00:00Z");

    private Recording besprechung;
    private Recording schulung;
    private Recording geteilt;
    private Recording fremd;

    @BeforeEach
    void setup() {
        search = new RecordingSearch(em);

        // Eigene Aufnahmen
        besprechung = recording(ich, "Wochenbesprechung Technik", "https://bbb.example/b/abc",
                Recording.Source.BOT, JETZT.minus(1, ChronoUnit.DAYS));
        schulung = recording(ich, "Schulung Neulinge", "https://bbb.example/b/xyz",
                Recording.Source.UPLOAD, JETZT.minus(10, ChronoUnit.DAYS));
        // Aufnahme des Kollegen, direkt mit mir geteilt
        geteilt = recording(kollege, "Vorstand August", null,
                Recording.Source.CAPTURE, JETZT.minus(3, ChronoUnit.DAYS));
        em.persist(ShareGrant.forUser(geteilt.getId(), ich, kollege));
        // Aufnahme eines Fremden, nicht geteilt
        fremd = recording(fremder, "Geheimes Gespraech", "https://bbb.example/b/geheim",
                Recording.Source.BOT, JETZT.minus(2, ChronoUnit.DAYS));
        em.flush();
    }

    // ------------------------------------------------------------ Sichtbarkeit

    @Test
    void zeigtNurZugaenglicheAufnahmen() {
        assertThat(ids(search.search(ich, RecordingFilter.NONE, RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactlyInAnyOrder(besprechung.getId(), schulung.getId(), geteilt.getId())
                .doesNotContain(fremd.getId());
    }

    @Test
    void freigabeUeberEineGruppeZaehltAuch() {
        UserGroup gruppe = UserGroup.create("Technik", kollege);
        em.persist(gruppe);
        em.persist(GroupMember.create(gruppe.getId(), ich));
        em.persist(ShareGrant.forGroup(fremd.getId(), gruppe.getId(), fremder));
        em.flush();

        assertThat(ids(search.search(ich, RecordingFilter.NONE, RecordingSort.DEFAULT, Pageable.unpaged())))
                .contains(fremd.getId());
    }

    @Test
    void eigeneGruppeAlsEmpfaengerZaehltEbenfalls() {
        // Gruppe, die MIR gehoert - eine Freigabe an sie erreicht mich auch dann,
        // wenn ich nicht als Mitglied eingetragen bin.
        UserGroup meine = UserGroup.create("Meine Runde", ich);
        em.persist(meine);
        em.persist(ShareGrant.forGroup(fremd.getId(), meine.getId(), fremder));
        em.flush();

        assertThat(ids(search.search(ich, RecordingFilter.NONE, RecordingSort.DEFAULT, Pageable.unpaged())))
                .contains(fremd.getId());
    }

    // ------------------------------------------------------------ Textsuche

    @Test
    void findetImTitelUnabhaengigVonGrossschreibung() {
        assertThat(ids(find("WOCHEN"))).containsExactly(besprechung.getId());
        assertThat(ids(find("neulinge"))).containsExactly(schulung.getId());
    }

    @Test
    void findetInDerMeetingUrl() {
        assertThat(ids(find("b/xyz"))).containsExactly(schulung.getId());
    }

    @Test
    void findetUeberSchlagwort() {
        em.persist(RecordingTag.create(schulung.getId(), "Projekt Nord"));
        em.flush();

        assertThat(ids(find("projekt"))).containsExactly(schulung.getId());
    }

    @Test
    void durchsuchtTranskriptNurAufWunsch() {
        em.persist(segment(besprechung.getId(), "[00:01] Die Haushaltsmittel sind bewilligt."));
        em.flush();

        assertThat(ids(find("haushaltsmittel"))).isEmpty();
        assertThat(ids(findInContent("haushaltsmittel"))).containsExactly(besprechung.getId());
    }

    @Test
    void findetNurInDerAktuellenFassungDerZusammenfassung() {
        em.persist(summary(schulung.getId(), "# Beschluss: Termin steht\n", true));
        em.persist(summary(besprechung.getId(), "# Verworfener Beschluss\n", false));
        em.flush();

        assertThat(ids(findInContent("beschluss"))).containsExactly(schulung.getId());
    }

    @Test
    void entschaerftPlatzhalterInDerEingabe() {
        Recording rabatt = recording(ich, "Rabatt 50% Aktion", null,
                Recording.Source.UPLOAD, JETZT);
        em.flush();

        // "50%" darf nicht als LIKE-Platzhalter wirken, sondern muss sich selbst suchen
        assertThat(ids(find("50%"))).containsExactly(rabatt.getId());
        assertThat(ids(find("50_"))).isEmpty();
    }

    // ------------------------------------------------------------ Filter

    @Test
    void schlagwortfilterSchraenktEin() {
        em.persist(RecordingTag.create(besprechung.getId(), "Protokoll"));
        em.flush();

        assertThat(ids(search.search(ich, filter(null, "Protokoll"), RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactly(besprechung.getId());
        assertThat(ids(search.search(ich, filter(null, "unbekannt"), RecordingSort.DEFAULT, Pageable.unpaged()))).isEmpty();
    }

    @Test
    void zeitraumfilterGrenztObenUndUnten() {
        assertThat(ids(search.search(ich, RecordingFilter.of(null, null, false,
                        JETZT.minus(4, ChronoUnit.DAYS).toString(), null, null, null),
                        RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactlyInAnyOrder(besprechung.getId(), geteilt.getId());

        assertThat(ids(search.search(ich, RecordingFilter.of(null, null, false,
                        null, JETZT.minus(5, ChronoUnit.DAYS).toString(), null, null),
                        RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactly(schulung.getId());
    }

    @Test
    void quellenfilter() {
        assertThat(ids(search.search(ich, RecordingFilter.of(null, null, false, null, null,
                        "upload", null), RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactly(schulung.getId());
        assertThat(ids(search.search(ich, RecordingFilter.of(null, null, false, null, null,
                        "CAPTURE", null), RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactly(geteilt.getId());
    }

    @Test
    void besitzerfilterEigeneUndGeteilte() {
        assertThat(ids(search.search(ich, RecordingFilter.of(null, null, false, null, null,
                        null, "mine"), RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactlyInAnyOrder(besprechung.getId(), schulung.getId());
        assertThat(ids(search.search(ich, RecordingFilter.of(null, null, false, null, null,
                        null, "shared"), RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactly(geteilt.getId());
        assertThat(ids(search.search(ich, RecordingFilter.of(null, null, false, null, null,
                        null, kollege.toString()), RecordingSort.DEFAULT, Pageable.unpaged())))
                .containsExactly(geteilt.getId());
    }

    @Test
    void nenntDieBesitzerDerFreigegebenenAufnahmen() {
        assertThat(search.sharingOwnerIds(ich)).containsExactly(kollege);
        // Wer nichts geteilt bekommt, bekommt auch keine Auswahlliste.
        assertThat(search.sharingOwnerIds(fremder)).isEmpty();
    }

    // ------------------------------------------------------------ Seiten und Sortierung

    @Test
    void liefertSeitenMitGesamtzahl() {
        Page<Recording> erste = search.search(ich, RecordingFilter.NONE,
                RecordingSort.of("date", "desc"), PageRequest.of(0, 2));

        assertThat(erste.getTotalElements()).isEqualTo(3);
        assertThat(erste.getTotalPages()).isEqualTo(2);
        assertThat(erste.hasNext()).isTrue();
        // Neueste zuerst: besprechung (-1 Tag), geteilt (-3 Tage)
        assertThat(ids(erste)).containsExactly(besprechung.getId(), geteilt.getId());

        Page<Recording> zweite = search.search(ich, RecordingFilter.NONE,
                RecordingSort.of("date", "desc"), PageRequest.of(1, 2));
        assertThat(zweite.hasNext()).isFalse();
        assertThat(ids(zweite)).containsExactly(schulung.getId());
    }

    @Test
    void sortiertAufsteigendNachDatum() {
        Page<Recording> alle = search.search(ich, RecordingFilter.NONE,
                RecordingSort.of("date", "asc"), Pageable.unpaged());

        assertThat(ids(alle)).containsExactly(schulung.getId(), geteilt.getId(), besprechung.getId());
    }

    /**
     * Nach Titel sortiert wird ueber das, was die Liste anzeigt: Fehlt der Titel,
     * zaehlt die Meeting-URL. Verglichen wird kleingeschrieben, damit die
     * Reihenfolge nicht von der Kollation der Datenbank abhaengt -
     * "https://..." steht damit vor "Schulung".
     */
    @Test
    void sortiertNachDemAngezeigtenTitel() {
        Recording ohneTitel = recording(ich, null, "https://bbb.example/b/ohne",
                Recording.Source.BOT, JETZT);
        em.flush();

        List<UUID> sortiert = ids(search.search(ich, RecordingFilter.NONE,
                RecordingSort.of("title", "asc"), Pageable.unpaged()));

        assertThat(sortiert).containsExactly(ohneTitel.getId(), schulung.getId(),
                geteilt.getId(), besprechung.getId());
    }

    // ------------------------------------------------------------ Hilfsmittel

    private Page<Recording> find(String text) {
        return search.search(ich, filter(text, null), RecordingSort.DEFAULT, Pageable.unpaged());
    }

    private Page<Recording> findInContent(String text) {
        return search.search(ich, RecordingFilter.of(text, null, true, null, null, null, null),
                RecordingSort.DEFAULT, Pageable.unpaged());
    }

    private static RecordingFilter filter(String text, String tag) {
        return RecordingFilter.of(text, tag, false, null, null, null, null);
    }

    private static List<UUID> ids(Page<Recording> page) {
        return page.getContent().stream().map(Recording::getId).toList();
    }

    /**
     * Aufnahme anlegen. {@code startedAt} setzt der Konstruktor auf "jetzt"; fuer
     * Zeitraum- und Sortierpruefungen wird es danach gezielt umgeschrieben (es
     * gibt bewusst keinen Setter dafuer in der Produktionsklasse).
     */
    private Recording recording(UUID ownerId, String title, String meetingUrl,
                                Recording.Source source, Instant startedAt) {
        Recording r = Recording.start(null, ownerId, meetingUrl, "/tmp/" + UUID.randomUUID(),
                false, true, false);
        r.setTitle(title);
        r.setSource(source);
        em.persist(r);
        em.flush();
        em.createQuery("update Recording r set r.startedAt = :t where r.id = :id")
                .setParameter("t", startedAt)
                .setParameter("id", r.getId())
                .executeUpdate();
        em.refresh(r);
        return r;
    }

    private static RecordingSegment segment(UUID recordingId, String transcript) {
        RecordingSegment s = RecordingSegment.create(recordingId, 0, "/tmp/x.webm");
        s.setStatus(RecordingSegment.Status.READY);
        s.setTranscriptText(transcript);
        return s;
    }

    private static Summary summary(UUID recordingId, String markdown, boolean current) {
        Summary s = Summary.create(recordingId);
        s.setStatus(Summary.Status.DONE);
        s.setMarkdown(markdown);
        s.setCurrent(current);
        return s;
    }
}
