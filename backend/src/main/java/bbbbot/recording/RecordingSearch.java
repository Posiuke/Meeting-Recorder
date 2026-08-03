package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingTag;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.RecordingTagRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Suche in den Aufnahmen, die ein Nutzer sehen darf.
 *
 * <p>Bewusst als Schnittmenge mehrerer schlanker Abfragen statt einer einzigen
 * grossen JPQL-Abfrage: Die Zugriffsliste holt ohnehin schon jede Listenansicht,
 * Titel und Meeting-URL lassen sich darauf direkt pruefen, und fuer Schlagworte,
 * Transkript und Zusammenfassung genuegt je eine ID-Abfrage. Das bleibt lesbar
 * und die Berechtigungslogik lebt weiter an genau einer Stelle.
 */
@Service
public class RecordingSearch {

    /** Laengengrenze des Suchbegriffs (schuetzt vor sinnlos teuren LIKE-Abfragen). */
    public static final int MAX_QUERY_LENGTH = 200;

    /** Fluchtzeichen fuer LIKE-Sonderzeichen; muss zum {@code escape} in den Abfragen passen. */
    private static final char LIKE_ESCAPE = '!';

    private final RecordingRepo recordingRepo;
    private final RecordingTagRepo tagRepo;
    private final RecordingSegmentRepo segmentRepo;
    private final SummaryRepo summaryRepo;

    public RecordingSearch(RecordingRepo recordingRepo, RecordingTagRepo tagRepo,
                           RecordingSegmentRepo segmentRepo, SummaryRepo summaryRepo) {
        this.recordingRepo = recordingRepo;
        this.tagRepo = tagRepo;
        this.segmentRepo = segmentRepo;
        this.summaryRepo = summaryRepo;
    }

    /**
     * Findet die Aufnahmen, die der Nutzer sehen darf und die den Filtern
     * entsprechen; Reihenfolge wie in der Listenansicht (neueste zuerst).
     *
     * @param text           Suchbegriff fuer Titel, Meeting-URL und Schlagworte;
     *                       leer = kein Textfilter
     * @param tag            genau dieses Schlagwort (ohne Gross-/Kleinschreibung);
     *                       leer = kein Schlagwortfilter
     * @param includeContent zusaetzlich in Transkript und Zusammenfassung suchen
     */
    public List<Recording> search(UUID userId, String text, String tag, boolean includeContent) {
        List<Recording> accessible = recordingRepo.findAllAccessibleBy(userId);

        String tagKey = RecordingTag.normalizeKey(tag);
        if (!tagKey.isEmpty()) {
            Set<UUID> tagged = new HashSet<>(tagRepo.findRecordingIdsByNameKey(tagKey));
            accessible = accessible.stream().filter(r -> tagged.contains(r.getId())).toList();
        }

        String needle = text == null ? "" : text.strip().toLowerCase(Locale.GERMAN);
        if (needle.isEmpty() || accessible.isEmpty()) return accessible;
        if (needle.length() > MAX_QUERY_LENGTH) needle = needle.substring(0, MAX_QUERY_LENGTH);

        String pattern = "%" + escapeLike(needle) + "%";
        Set<UUID> hits = new HashSet<>(tagRepo.findRecordingIdsByNameKeyLike(pattern));
        if (includeContent) {
            hits.addAll(segmentRepo.findRecordingIdsByTranscriptLike(pattern));
            hits.addAll(summaryRepo.findRecordingIdsByMarkdownLike(pattern));
        }

        final String search = needle;
        return accessible.stream()
                .filter(r -> matchesMetadata(r, search) || hits.contains(r.getId()))
                .toList();
    }

    /** Titel (bei Bot-Aufnahmen der Raumname) und Meeting-URL. */
    private static boolean matchesMetadata(Recording recording, String needle) {
        return contains(recording.getTitle(), needle) || contains(recording.getMeetingUrl(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.GERMAN).contains(needle);
    }

    /**
     * Wildcards des Nutzers entschaerfen: Ein eingegebenes % oder _ soll sich
     * selbst suchen und nicht als LIKE-Platzhalter wirken.
     */
    private static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) escaped.append(LIKE_ESCAPE);
            escaped.append(c);
        }
        return escaped.toString();
    }
}
