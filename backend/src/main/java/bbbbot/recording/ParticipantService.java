package bbbbot.recording;

import bbbbot.domain.Participant;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.ParticipantRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.stt.TranscriptAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verwaltet die Teilnehmerliste einer Aufnahme: Nach der Transkription werden
 * die von der Diarisierung erkannten Sprecher (SPEAKER_xx) als Teilnehmer
 * persistiert. Der Anzeigename ist editierbar und ersetzt das rohe Label bei
 * der Transkript-Anzeige, in transcript.md und in neuen Zusammenfassungen.
 * Die Segment-Rohdaten behalten immer die Original-Labels, damit die Zuordnung
 * auch nach Umbenennungen stabil bleibt.
 */
@Service
public class ParticipantService {

    private static final Logger log = LoggerFactory.getLogger(ParticipantService.class);
    private static final Pattern SPEAKER_ID = Pattern.compile("^SPEAKER_(\\d+)$");

    private final ParticipantRepo participantRepo;
    private final RecordingSegmentRepo segmentRepo;

    /**
     * Aufnahmen, deren Transkript bereits auf Sprecher geprueft wurde. Der
     * Lazy-Backfill fuer Alt-Aufnahmen (ensureFromTranscript) parst das gesamte
     * Transkript - das darf pro Aufnahme und Prozesslauf hoechstens einmal
     * passieren, sonst laeuft es bei Aufnahmen ohne Sprecher-Labels auf jedem
     * Detail-Abruf (inkl. 4s-Polling) erneut.
     */
    private final Set<UUID> backfillChecked = ConcurrentHashMap.newKeySet();

    public ParticipantService(ParticipantRepo participantRepo, RecordingSegmentRepo segmentRepo) {
        this.participantRepo = participantRepo;
        this.segmentRepo = segmentRepo;
    }

    public List<Participant> list(UUID recordingId) {
        return participantRepo.findByRecordingIdOrderBySpeakerLabelAsc(recordingId);
    }

    /**
     * Legt fuer bisher unbekannte Sprecher-Labels neue Teilnehmer an.
     * Vorhandene Teilnehmer (inkl. bereits umbenannter) bleiben unveraendert,
     * damit Umbenennungen eine erneute Transkription ueberleben. Legt ein
     * paralleler Aufruf dieselben Labels gleichzeitig an, gewinnt der erste -
     * der Unique-Index-Konflikt wird abgefangen statt den Aufrufer (z.B. einen
     * Detail-GET) mit einem 500er scheitern zu lassen.
     */
    public void syncFromEntries(UUID recordingId, List<TranscriptAssembler.Entry> entries) {
        Set<String> known = list(recordingId).stream()
                .map(Participant::getSpeakerLabel)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Participant> created = new ArrayList<>();
        LinkedHashSet<String> labels = entries.stream()
                .map(TranscriptAssembler.Entry::speaker)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String label : labels) {
            if (known.contains(label)) continue;
            created.add(Participant.forSpeaker(recordingId, label, defaultDisplayName(label)));
        }
        if (created.isEmpty()) return;
        try {
            participantRepo.saveAll(created);
            log.info("{} Teilnehmer aus Transkription von Aufnahme {} angelegt", created.size(), recordingId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.debug("Teilnehmer von Aufnahme {} wurden zeitgleich von anderer Stelle angelegt", recordingId);
        }
    }

    /**
     * Teilnehmerliste laden und dabei fuer aeltere, vor Einfuehrung der
     * Teilnehmerliste transkribierte Aufnahmen die Sprecher einmalig aus dem
     * vorhandenen Transkript nachtragen. Das teure Transkript-Parsen laeuft
     * hoechstens einmal pro Aufnahme (siehe backfillChecked).
     */
    public List<Participant> ensureFromTranscript(Recording recording) {
        List<Participant> existing = list(recording.getId());
        if (!existing.isEmpty()) {
            backfillChecked.add(recording.getId());
            return existing;
        }
        if (recording.getStatus() != Recording.Status.TRANSCRIBED
                && recording.getStatus() != Recording.Status.DONE) {
            return existing;
        }
        // add() liefert false, wenn diese Aufnahme schon geprueft wurde
        if (!backfillChecked.add(recording.getId())) return existing;
        syncFromEntries(recording.getId(),
                TranscriptAssembler.assemble(segmentRepo.findByRecordingIdOrderBySeq(recording.getId())));
        return list(recording.getId());
    }

    /** Zuordnung Diarisierungs-Label -> aktueller Anzeigename. */
    public Map<String, String> nameMap(UUID recordingId) {
        Map<String, String> map = new HashMap<>();
        for (Participant p : list(recordingId)) {
            if (p.getSpeakerLabel() != null) {
                map.put(p.getSpeakerLabel(), p.getDisplayName());
            }
        }
        return map;
    }

    /** Standardname fuer ein Diarisierungs-Label: SPEAKER_00 -> "Sprecher 1". */
    public static String defaultDisplayName(String label) {
        Matcher m = SPEAKER_ID.matcher(label);
        return m.matches() ? "Sprecher " + (Integer.parseInt(m.group(1)) + 1) : label;
    }
}
