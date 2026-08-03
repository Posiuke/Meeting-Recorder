package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingTag;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingTagRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Schlagworte von Aufnahmen: anlegen, entfernen, auflisten. Die Schlagworte
 * gehoeren zur Aufnahme - wer sie aendern darf, entscheidet der Aufrufer
 * (Besitzer), wer sie sehen darf, ergibt sich aus der Leseberechtigung.
 */
@Service
public class RecordingTagService {

    private static final Logger log = LoggerFactory.getLogger(RecordingTagService.class);

    /** Obergrenze je Aufnahme - haelt die Anzeige lesbar und Tippfehler-Wildwuchs klein. */
    public static final int MAX_TAGS_PER_RECORDING = 20;

    private final RecordingTagRepo tagRepo;
    private final RecordingRepo recordingRepo;

    public RecordingTagService(RecordingTagRepo tagRepo, RecordingRepo recordingRepo) {
        this.tagRepo = tagRepo;
        this.recordingRepo = recordingRepo;
    }

    /** Ein Schlagwort samt Haeufigkeit fuer Filterleiste und Vorschlagsliste. */
    public record TagCount(String name, long count) {}

    /** Schlagworte einer Aufnahme in stabiler Reihenfolge (Anzeigeform). */
    public List<String> tagsOf(UUID recordingId) {
        return tagRepo.findByRecordingIdOrderByNameKeyAsc(recordingId).stream()
                .map(RecordingTag::getName)
                .toList();
    }

    /**
     * Schlagworte zu mehreren Aufnahmen in EINER Abfrage - fuer die Listenansicht,
     * die sonst pro Zeile nachladen wuerde.
     */
    public Map<UUID, List<String>> tagsOf(List<Recording> recordings) {
        if (recordings.isEmpty()) return Map.of();
        List<UUID> ids = recordings.stream().map(Recording::getId).toList();
        Map<UUID, List<String>> byRecording = new HashMap<>();
        List<RecordingTag> tags = new ArrayList<>(tagRepo.findByRecordingIdIn(ids));
        tags.sort(Comparator.comparing(RecordingTag::getNameKey));
        for (RecordingTag tag : tags) {
            byRecording.computeIfAbsent(tag.getRecordingId(), k -> new ArrayList<>()).add(tag.getName());
        }
        return byRecording;
    }

    /**
     * Alle Schlagworte, die der Nutzer sehen kann, mit Anzahl der Aufnahmen -
     * haeufigste zuerst. Grundlage fuer die Filterleiste und die Vorschlagsliste
     * im Eingabefeld.
     */
    public List<TagCount> visibleTags(UUID userId) {
        List<Recording> accessible = recordingRepo.findAllAccessibleBy(userId);
        if (accessible.isEmpty()) return List.of();
        List<RecordingTag> tags = tagRepo.findByRecordingIdIn(
                accessible.stream().map(Recording::getId).toList());

        // Nach Vergleichsform gruppieren; als Anzeigeform die alphabetisch erste
        // Schreibweise nehmen, damit die Liste bei gemischter Eingabe stabil bleibt.
        Map<String, String> display = new LinkedHashMap<>();
        Map<String, Long> counts = new HashMap<>();
        for (RecordingTag tag : tags) {
            counts.merge(tag.getNameKey(), 1L, Long::sum);
            display.merge(tag.getNameKey(), tag.getName(),
                    (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        return counts.entrySet().stream()
                .map(e -> new TagCount(display.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingLong(TagCount::count).reversed()
                        .thenComparing(TagCount::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Schlagwort hinzufuegen. Bereits vorhandene Schlagworte (unabhaengig von
     * Gross-/Kleinschreibung) werden stillschweigend uebergangen, damit ein
     * doppelter Klick kein Fehler ist.
     *
     * @return die Schlagworte der Aufnahme nach der Aenderung
     * @throws IllegalArgumentException bei leerem, zu langem oder zu vielem Schlagwort
     */
    public List<String> addTag(UUID recordingId, String rawName) {
        String name = RecordingTag.normalizeName(rawName);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Schlagwort darf nicht leer sein");
        }
        if (name.length() > RecordingTag.MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Schlagwort ist zu lang (max. " + RecordingTag.MAX_LENGTH + " Zeichen)");
        }
        String key = RecordingTag.normalizeKey(name);
        if (tagRepo.findByRecordingIdAndNameKey(recordingId, key).isEmpty()) {
            if (tagRepo.countByRecordingId(recordingId) >= MAX_TAGS_PER_RECORDING) {
                throw new IllegalArgumentException(
                        "Mehr als " + MAX_TAGS_PER_RECORDING + " Schlagworte je Aufnahme sind nicht moeglich");
            }
            tagRepo.save(RecordingTag.create(recordingId, name));
            log.debug("Aufnahme {}: Schlagwort '{}' hinzugefuegt", recordingId, name);
        }
        return tagsOf(recordingId);
    }

    /**
     * Schlagwort entfernen (Vergleich ohne Gross-/Kleinschreibung). Ein nicht
     * vorhandenes Schlagwort ist kein Fehler.
     *
     * @return die Schlagworte der Aufnahme nach der Aenderung
     */
    public List<String> removeTag(UUID recordingId, String rawName) {
        String key = RecordingTag.normalizeKey(rawName);
        tagRepo.findByRecordingIdAndNameKey(recordingId, key).ifPresent(tag -> {
            tagRepo.delete(tag);
            log.debug("Aufnahme {}: Schlagwort '{}' entfernt", recordingId, tag.getName());
        });
        return tagsOf(recordingId);
    }
}
