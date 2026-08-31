package bbbbot.recording;

import bbbbot.domain.GroupMember;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.RecordingTag;
import bbbbot.domain.ShareGrant;
import bbbbot.domain.Summary;
import bbbbot.domain.UserGroup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Suche in den Aufnahmen, die ein Nutzer sehen darf - Berechtigung, Filter,
 * Sortierung und Seitenaufteilung in einer Abfrage.
 *
 * <p>Frueher war das eine Schnittmenge mehrerer schlanker Abfragen, die in Java
 * zusammengefuehrt wurden. Das war gut lesbar, hat aber immer den ganzen
 * Bestand geladen - und liess sich nicht seitenweise abrufen: Wer nur die
 * ersten 25 Treffer will, muss die Einschraenkung der Datenbank ueberlassen,
 * sonst ist nichts gewonnen.
 *
 * <p>Gebaut ist die Abfrage mit der Criteria-API statt als JPQL-Zeichenkette:
 * Fast jeder Filter ist optional, und eine Zeichenkette mit einem Dutzend
 * {@code :param is null}-Klauseln waere schwer zu lesen und leicht falsch zu
 * aendern. Bewusst <b>ohne</b> {@code JpaSpecificationExecutor} am Repository -
 * das haengt dort ein {@code delete(Specification)} an, das niemand braucht und
 * das sich leicht versehentlich treffen laesst.
 *
 * <p>Die Berechtigungslogik lebt an genau einer Stelle
 * ({@link #accessible}) und wird auch von {@link #sharingOwnerIds} benutzt.
 *
 * <p><b>Volltextsuche:</b> Transkript und Zusammenfassung werden weiterhin per
 * {@code LIKE} durchsucht, unveraendert zur bisherigen Umsetzung. Ein Index
 * ({@code pg_trgm} oder {@code tsvector}) lohnt erst, wenn die Bestaende es
 * verlangen; er waere dann eine Migration plus eine geaenderte Bedingung hier.
 */
@Service
public class RecordingSearch {

    /** Laengengrenze des Suchbegriffs (schuetzt vor sinnlos teuren LIKE-Abfragen). */
    public static final int MAX_QUERY_LENGTH = 200;

    /** Fluchtzeichen fuer LIKE-Sonderzeichen. */
    static final char LIKE_ESCAPE = '!';

    private final EntityManager em;

    public RecordingSearch(EntityManager em) {
        this.em = em;
    }

    /**
     * Eine Seite der Aufnahmen, die der Nutzer sehen darf und die den Filtern
     * entsprechen.
     *
     * @param pageable Seite und Groesse; {@link Pageable#unpaged()} liefert alles.
     *                 Eine Sortierung am Pageable wird nicht ausgewertet - sie
     *                 kommt aus {@code sort}.
     */
    public Page<Recording> search(UUID userId, RecordingFilter filter, RecordingSort sort,
                                  Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // Die Gruppen des Nutzers gelten fuer Treffer- und Zaehlabfrage gleich -
        // einmal holen genuegt.
        List<UUID> groupIds = groupIdsOf(userId);

        CriteriaQuery<Recording> criteria = cb.createQuery(Recording.class);
        Root<Recording> root = criteria.from(Recording.class);
        criteria.where(where(cb, criteria, root, userId, groupIds, filter));
        criteria.orderBy(orderBy(cb, root, sort));

        TypedQuery<Recording> query = em.createQuery(criteria);
        if (pageable.isPaged()) {
            query.setFirstResult((int) pageable.getOffset());
            query.setMaxResults(pageable.getPageSize());
        }
        List<Recording> content = query.getResultList();
        // Zaehlt nur, wenn es noetig ist: Bei einer unvollstaendig gefuellten
        // ersten Seite steht die Gesamtzahl schon fest.
        return PageableExecutionUtils.getPage(content, pageable,
                () -> count(cb, userId, groupIds, filter));
    }

    private long count(CriteriaBuilder cb, UUID userId, List<UUID> groupIds, RecordingFilter filter) {
        CriteriaQuery<Long> criteria = cb.createQuery(Long.class);
        Root<Recording> root = criteria.from(Recording.class);
        criteria.select(cb.count(root))
                .where(where(cb, criteria, root, userId, groupIds, filter));
        return em.createQuery(criteria).getSingleResult();
    }

    /**
     * Die Nutzer, die dem angemeldeten Nutzer mindestens eine Aufnahme
     * freigegeben haben - die Auswahlliste des Besitzerfilters.
     *
     * <p>Eigene Abfrage nur auf die Besitzerspalte statt "alle Aufnahmen laden
     * und Besitzer sammeln": Fuer eine Auswahlliste den ganzen Bestand zu holen
     * waere genau der Fehler, den die Seitenaufteilung gerade abstellt.
     */
    public List<UUID> sharingOwnerIds(UUID userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UUID> criteria = cb.createQuery(UUID.class);
        Root<Recording> root = criteria.from(Recording.class);
        criteria.select(root.get("ownerId")).distinct(true)
                .where(cb.and(accessible(cb, criteria, root, userId, groupIdsOf(userId)),
                        cb.notEqual(root.get("ownerId"), userId)));
        return em.createQuery(criteria).getResultList();
    }

    // ------------------------------------------------------------ Bedingungen

    /** Sichtbarkeit und alle gesetzten Filter als eine Bedingung. */
    private Predicate where(CriteriaBuilder cb, AbstractQuery<?> query, Root<Recording> root,
                            UUID userId, List<UUID> groupIds, RecordingFilter filter) {
        List<Predicate> and = new ArrayList<>();
        and.add(accessible(cb, query, root, userId, groupIds));

        switch (filter.ownerScope()) {
            case MINE -> and.add(cb.equal(root.get("ownerId"), userId));
            case SHARED -> and.add(cb.notEqual(root.get("ownerId"), userId));
            case ALL -> { /* keine Einschraenkung */ }
        }
        if (filter.ownerId() != null) {
            and.add(cb.equal(root.get("ownerId"), filter.ownerId()));
        }
        if (filter.from() != null) {
            and.add(cb.greaterThanOrEqualTo(root.get("startedAt"), filter.from()));
        }
        if (filter.to() != null) {
            and.add(cb.lessThan(root.get("startedAt"), filter.to()));
        }
        if (filter.source() != null) {
            and.add(cb.equal(root.get("source"), filter.source()));
        }
        if (filter.tagKey() != null) {
            and.add(cb.exists(tagSubquery(query, cb, root,
                    tag -> cb.equal(tag.get("nameKey"), filter.tagKey()))));
        }
        if (filter.text() != null) {
            and.add(textPredicate(query, cb, root, filter));
        }
        return cb.and(and.toArray(Predicate[]::new));
    }

    /**
     * Sichtbarkeit: eigene Aufnahmen plus alles, was direkt oder ueber eine
     * Gruppe mit dem Nutzer geteilt wurde.
     *
     * <p>Die Gruppen des Nutzers kommen als Liste herein, statt als
     * verschachtelte Unterabfrage mitzulaufen: Es sind wenige Kennungen, und die
     * Bedingung bleibt so eine Ebene flach.
     */
    private Predicate accessible(CriteriaBuilder cb, AbstractQuery<?> query, Root<Recording> root,
                                 UUID userId, List<UUID> groupIds) {
        Subquery<UUID> shared = query.subquery(UUID.class);
        Root<ShareGrant> grant = shared.from(ShareGrant.class);
        shared.select(grant.get("recordingId"));
        Predicate grantedToUser = cb.equal(grant.get("granteeUserId"), userId);
        // Leere Gruppenliste: Ein "in ()" gibt es in SQL nicht - dann bleibt nur
        // die direkte Freigabe an den Nutzer.
        shared.where(groupIds.isEmpty()
                ? grantedToUser
                : cb.or(grantedToUser, grant.get("granteeGroupId").in(groupIds)));

        return cb.or(cb.equal(root.get("ownerId"), userId), root.get("id").in(shared));
    }

    /**
     * Gruppen, die dem Nutzer gehoeren oder in denen er Mitglied ist - dieselbe
     * Menge wie bei {@code UserGroupRepo.findAllVisibleTo}.
     */
    private List<UUID> groupIdsOf(UUID userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UUID> owned = cb.createQuery(UUID.class);
        Root<UserGroup> group = owned.from(UserGroup.class);
        owned.select(group.get("id")).where(cb.equal(group.get("ownerId"), userId));

        CriteriaQuery<UUID> member = cb.createQuery(UUID.class);
        Root<GroupMember> membership = member.from(GroupMember.class);
        member.select(membership.get("groupId")).where(cb.equal(membership.get("userId"), userId));

        List<UUID> ids = new ArrayList<>(em.createQuery(owned).getResultList());
        for (UUID id : em.createQuery(member).getResultList()) {
            if (!ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /**
     * Der Suchbegriff trifft, wenn er in Titel, Meeting-URL oder einem
     * Schlagwort steckt - und auf Wunsch zusaetzlich in Transkript oder
     * aktueller Zusammenfassung.
     */
    private Predicate textPredicate(AbstractQuery<?> query, CriteriaBuilder cb,
                                    Root<Recording> root, RecordingFilter filter) {
        String pattern = "%" + escapeLike(filter.text()) + "%";
        List<Predicate> or = new ArrayList<>();
        or.add(like(cb, root.get("title"), pattern));
        or.add(like(cb, root.get("meetingUrl"), pattern));
        or.add(cb.exists(tagSubquery(query, cb, root, tag -> like(cb, tag.get("nameKey"), pattern))));

        if (filter.includeContent()) {
            // Transkript: die Rohfassung von Whisper, wie bisher.
            Subquery<UUID> segments = query.subquery(UUID.class);
            Root<RecordingSegment> segment = segments.from(RecordingSegment.class);
            segments.select(segment.get("recordingId")).where(cb.and(
                    cb.equal(segment.get("recordingId"), root.get("id")),
                    cb.isNotNull(segment.get("transcriptText")),
                    like(cb, segment.get("transcriptText"), pattern)));
            or.add(cb.exists(segments));

            // Zusammenfassung: nur die aktuelle Fassung - ein Treffer soll in dem
            // Text stehen, den die Aufnahme auch anzeigt.
            Subquery<UUID> summaries = query.subquery(UUID.class);
            Root<Summary> summary = summaries.from(Summary.class);
            summaries.select(summary.get("recordingId")).where(cb.and(
                    cb.equal(summary.get("recordingId"), root.get("id")),
                    cb.isTrue(summary.get("current")),
                    cb.isNotNull(summary.get("markdown")),
                    like(cb, summary.get("markdown"), pattern)));
            or.add(cb.exists(summaries));
        }
        return cb.or(or.toArray(Predicate[]::new));
    }

    /** Unterabfrage auf die Schlagworte dieser Aufnahme mit einer zusaetzlichen Bedingung. */
    private Subquery<UUID> tagSubquery(AbstractQuery<?> query, CriteriaBuilder cb,
                                       Root<Recording> root, TagCondition condition) {
        Subquery<UUID> tags = query.subquery(UUID.class);
        Root<RecordingTag> tag = tags.from(RecordingTag.class);
        tags.select(tag.get("recordingId")).where(cb.and(
                cb.equal(tag.get("recordingId"), root.get("id")),
                condition.apply(tag)));
        return tags;
    }

    @FunctionalInterface
    private interface TagCondition {
        Predicate apply(Root<RecordingTag> tag);
    }

    // ------------------------------------------------------------ Sortierung

    /**
     * Reihenfolge der Treffer. Die Kennung ist immer das letzte Kriterium - das
     * ist keine Kosmetik, sondern Voraussetzung fuer seitenweises Laden: Bei
     * gleichem Datum (oder gleichem Titel) ist die Reihenfolge sonst nicht
     * festgelegt, und dieselbe Aufnahme kann auf zwei Seiten erscheinen - oder
     * auf keiner.
     */
    private List<Order> orderBy(CriteriaBuilder cb, Root<Recording> root, RecordingSort sort) {
        List<Order> orders = new ArrayList<>();
        if (sort.byTitle()) {
            // Kleingeschrieben sortieren: Sonst entscheidet die Kollation der
            // Datenbank, ob "https://..." vor oder hinter "Schulung" landet -
            // und dieselbe Liste saehe je nach Server anders aus.
            Expression<String> shown = cb.lower(
                    cb.coalesce(root.get("title"), root.get("meetingUrl")));
            orders.add(sort.ascending() ? cb.asc(shown) : cb.desc(shown));
            // Bei gleichem Titel die neuere Aufnahme zuerst - so stehen
            // wiederkehrende Termine in sich chronologisch beieinander.
            orders.add(cb.desc(root.get("startedAt")));
        } else {
            Expression<?> startedAt = root.get("startedAt");
            orders.add(sort.ascending() ? cb.asc(startedAt) : cb.desc(startedAt));
        }
        orders.add(cb.desc(root.get("id")));
        return orders;
    }

    /** Vergleich ohne Ruecksicht auf Gross-/Kleinschreibung; das Muster ist klein. */
    private static Predicate like(CriteriaBuilder cb, Expression<String> field, String pattern) {
        return cb.like(cb.lower(field), pattern, LIKE_ESCAPE);
    }

    /**
     * Wildcards des Nutzers entschaerfen: Ein eingegebenes % oder _ soll sich
     * selbst suchen und nicht als LIKE-Platzhalter wirken.
     */
    static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) escaped.append(LIKE_ESCAPE);
            escaped.append(c);
        }
        return escaped.toString();
    }
}
