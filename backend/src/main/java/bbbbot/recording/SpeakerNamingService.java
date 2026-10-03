package bbbbot.recording;

import bbbbot.domain.Participant;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.llm.LlmClient;
import bbbbot.repository.Repositories.ParticipantRepo;
import bbbbot.settings.SettingsService;
import bbbbot.stt.TranscriptAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schlaegt fuer die erkannten Sprecher einer Aufnahme Namen vor.
 *
 * <ol>
 *   <li><b>Sprechanzeige von BBB</b> (nur Bot-Aufnahmen): Der Bot hat
 *       protokolliert, wer wann gesprochen hat; abgeglichen mit den Zeiten der
 *       Diarisierung ist das die verlaesslichste Quelle ({@link TalkLogMatcher}).</li>
 *   <li><b>LLM</b> fuer alle uebrigen Sprecher: wertet Vorstellungen ("Ich bin
 *       Anna") und Ansprachen ("Was meinst du, Tom?") aus.</li>
 * </ol>
 *
 * <p>Vorschlaege werden am Teilnehmer abgelegt und vom Besitzer bestaetigt oder
 * verworfen. Nur mit {@code speakers.autoApply} werden Vorschlaege hoher
 * Sicherheit gleich uebernommen. Teilnehmer, die schon von Hand benannt sind,
 * bleiben immer unberuehrt. Fehler sind nie fatal fuer die Verarbeitung.
 */
@Service
public class SpeakerNamingService {

    private static final Logger log = LoggerFactory.getLogger(SpeakerNamingService.class);

    /** Transkript-Ausschnitt fuer das LLM: Vorstellungen stehen fast immer am Anfang. */
    private static final int MAX_TRANSCRIPT_CHARS = 12_000;
    /** Fuer spaet einsteigende Sprecher zusaetzlich ihre ersten Aeusserungen mit Umfeld. */
    private static final int LATE_SPEAKER_CONTEXT_LINES = 4;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EVIDENCE_LENGTH = 300;

    static final String SYSTEM_PROMPT = """
            Du ordnest die Sprecher-Labels eines automatisch erstellten Transkripts \
            (SPEAKER_00, SPEAKER_01, ...) den echten Namen der Personen zu.

            Hinweise, die du auswerten sollst:
            - Vorstellung ("Ich bin Anna", "mein Name ist ...") -> Name des AKTUELLEN Sprechers.
            - Direkte Ansprache ("Was meinst du, Tom?", "Danke, Anna") -> meist der NAECHSTE \
            bzw. VORHERIGE Sprecher, nicht der aktuelle.
            - Moderation: Wer begruesst, ueberleitet und verabschiedet, ist meist der Host.
            - Teilnehmerliste (falls angegeben): nur Namen daraus oder aus dem Transkript verwenden.

            Strenge Regeln:
            - Erfinde keine Namen. Personen, ueber die nur gesprochen wird, sind keine Sprecher.
            - Im Zweifel name = null. Ein falscher Name ist schlimmer als keiner.
            - Ein Name gehoert hoechstens zu einem Label.
            - confidence: "high" nur bei eindeutigem Beleg (z.B. Selbstvorstellung), \
            "medium" bei starkem Indiz, sonst "low".
            - evidence: die entscheidende Stelle woertlich mit Zeitstempel, z.B. \
            "[01:12] Hallo, ich bin Anna".

            Antworte ausschliesslich mit JSON in genau dieser Form, ohne weiteren Text:
            {"speakers":[{"label":"SPEAKER_00","name":"Anna","confidence":"high","evidence":"[01:12] ..."}]}""";

    private final SettingsService settings;
    private final ParticipantRepo participantRepo;
    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpeakerNamingService(SettingsService settings, ParticipantRepo participantRepo, LlmClient llm) {
        this.settings = settings;
        this.participantRepo = participantRepo;
        this.llm = llm;
    }

    public boolean isEnabled() {
        return settings.getBool(SettingsService.SPEAKERS_NAME_SUGGESTIONS);
    }

    /**
     * Ermittelt Vorschlaege fuer alle noch nicht benannten Sprecher und legt sie
     * an den Teilnehmern ab. Vorhandene, noch offene Vorschlaege werden ersetzt.
     *
     * @return Anzahl der Teilnehmer mit neuem Vorschlag (bzw. neu uebernommenem Namen)
     */
    public int suggest(Recording recording, List<RecordingSegment> segments) {
        List<Participant> unnamed = participantRepo.findByRecordingIdOrderBySpeakerLabelAsc(recording.getId())
                .stream()
                .filter(p -> p.getSpeakerLabel() != null)
                .filter(p -> p.getDisplayName().equals(ParticipantService.defaultDisplayName(p.getSpeakerLabel())))
                .toList();
        if (unnamed.isEmpty()) return 0;

        List<TranscriptAssembler.Entry> entries = TranscriptAssembler.assemble(segments, true);
        Map<String, Suggestion> suggestions = new LinkedHashMap<>();

        // 1. Sprechanzeige von BBB
        List<TalkLogMatcher.Interval> intervals = TalkLogMatcher.parse(recording.getTalkLog());
        if (!intervals.isEmpty()) {
            TalkLogMatcher.match(entries, intervals).forEach((label, m) ->
                    suggestions.put(label, new Suggestion(m.name(), m.confidence(), "BBB", m.evidence())));
            log.info("Sprechanzeige von Aufnahme {}: {} von {} Sprechern zugeordnet",
                    recording.getId(), suggestions.size(), unnamed.size());
        }

        // 2. LLM fuer alle, die die Sprechanzeige nicht sicher klaeren konnte
        Set<String> open = new LinkedHashSet<>();
        for (Participant p : unnamed) {
            Suggestion s = suggestions.get(p.getSpeakerLabel());
            if (s == null || !"HIGH".equals(s.confidence())) open.add(p.getSpeakerLabel());
        }
        if (!open.isEmpty() && !entries.isEmpty()) {
            askLlm(recording, entries, open, suggestions).forEach((label, s) -> {
                Suggestion existing = suggestions.get(label);
                if (existing == null || rank(s.confidence()) > rank(existing.confidence())) {
                    suggestions.put(label, s);
                }
            });
        }

        boolean autoApply = settings.getBool(SettingsService.SPEAKERS_AUTO_APPLY);
        int changed = 0;
        for (Participant p : unnamed) {
            Suggestion s = suggestions.get(p.getSpeakerLabel());
            if (s == null) {
                if (p.getSuggestedName() != null) {
                    p.clearSuggestion();
                    participantRepo.save(p);
                }
                continue;
            }
            if (autoApply && "HIGH".equals(s.confidence())) {
                p.setDisplayName(s.name());
                p.clearSuggestion();
                log.info("Sprecher {} von Aufnahme {} automatisch benannt: {} ({})",
                        p.getSpeakerLabel(), recording.getId(), s.name(), s.source());
            } else {
                p.suggest(s.name(), s.confidence(), s.source(), s.evidence());
            }
            participantRepo.save(p);
            changed++;
        }
        return changed;
    }

    record Suggestion(String name, String confidence, String source, String evidence) {}

    private static int rank(String confidence) {
        return switch (confidence) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private Map<String, Suggestion> askLlm(Recording recording, List<TranscriptAssembler.Entry> entries,
                                           Set<String> labels, Map<String, Suggestion> known) {
        String userPrompt = buildUserPrompt(recording, entries, labels, known);
        // Niedrige Temperatur: hier wird zugeordnet, nicht formuliert.
        LlmClient.LlmResult result = llm.chat(SYSTEM_PROMPT, userPrompt, new LlmClient.Overrides(null, 0.1, null));
        if (!result.success()) {
            log.warn("Namensvorschlaege per LLM fuer Aufnahme {} fehlgeschlagen: {}",
                    recording.getId(), result.error());
            return Map.of();
        }
        Map<String, Suggestion> parsed = parseLlmAnswer(result.content(), labels);
        log.info("LLM schlaegt fuer Aufnahme {} {} von {} offenen Sprechern einen Namen vor",
                recording.getId(), parsed.size(), labels.size());
        return parsed;
    }

    String buildUserPrompt(Recording recording, List<TranscriptAssembler.Entry> entries,
                           Set<String> labels, Map<String, Suggestion> known) {
        StringBuilder sb = new StringBuilder();
        sb.append("Zuzuordnende Sprecher (mit Redezeit):\n");
        Map<String, Long> talkTime = talkTime(entries);
        for (String label : labels) {
            sb.append("- ").append(label).append(" (")
                    .append(TranscriptAssembler.formatTime(talkTime.getOrDefault(label, 0L))).append(")\n");
        }
        known.forEach((label, s) -> {
            if ("HIGH".equals(s.confidence())) {
                sb.append("Bereits sicher zugeordnet: ").append(label).append(" = ").append(s.name()).append('\n');
            }
        });
        List<String> names = participantNames(recording.getParticipantsLog());
        if (!names.isEmpty()) {
            sb.append("\nTeilnehmerliste des Meetings: ").append(String.join(", ", names)).append('\n');
        }

        String full = TranscriptAssembler.toText(entries);
        String excerpt = full.length() <= MAX_TRANSCRIPT_CHARS ? full : cutAtLine(full, MAX_TRANSCRIPT_CHARS);
        sb.append("\nTranskript").append(excerpt.length() < full.length() ? " (Anfang)" : "").append(":\n")
                .append(excerpt).append('\n');

        // Sprecher, die im Ausschnitt nicht vorkommen: ihre ersten Aeusserungen mit Umfeld nachreichen.
        if (excerpt.length() < full.length()) {
            for (String label : labels) {
                if (excerpt.contains(label + ":")) continue;
                int first = -1;
                for (int i = 0; i < entries.size(); i++) {
                    if (label.equals(entries.get(i).speaker())) { first = i; break; }
                }
                if (first < 0) continue;
                int from = Math.max(0, first - LATE_SPEAKER_CONTEXT_LINES);
                int to = Math.min(entries.size(), first + LATE_SPEAKER_CONTEXT_LINES + 1);
                sb.append("\nAusschnitt um den ersten Beitrag von ").append(label).append(":\n")
                        .append(TranscriptAssembler.toText(entries.subList(from, to))).append('\n');
            }
        }
        return sb.toString();
    }

    Map<String, Suggestion> parseLlmAnswer(String content, Set<String> labels) {
        Map<String, Suggestion> result = new LinkedHashMap<>();
        if (content == null) return result;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return result;
        try {
            JsonNode root = mapper.readTree(content.substring(start, end + 1));
            JsonNode speakers = root.isArray() ? root : root.path("speakers");
            Set<String> usedNames = new java.util.HashSet<>();
            for (JsonNode s : speakers) {
                String label = s.path("label").asText("").trim();
                String name = s.path("name").isTextual() ? s.path("name").asText().trim() : "";
                if (!labels.contains(label) || name.isEmpty() || name.length() > MAX_NAME_LENGTH
                        || name.equalsIgnoreCase("null") || name.toUpperCase(Locale.ROOT).startsWith("SPEAKER_")) {
                    continue;
                }
                // Derselbe Name fuer zwei Labels widerspricht den Regeln - dann keinem trauen.
                if (!usedNames.add(name.toLowerCase(Locale.ROOT))) {
                    result.values().removeIf(v -> v.name().equalsIgnoreCase(name));
                    continue;
                }
                String confidence = switch (s.path("confidence").asText("low").trim().toLowerCase(Locale.ROOT)) {
                    case "high" -> "HIGH";
                    case "medium" -> "MEDIUM";
                    default -> "LOW";
                };
                String evidence = s.path("evidence").asText("").trim();
                if (evidence.length() > MAX_EVIDENCE_LENGTH) {
                    evidence = evidence.substring(0, MAX_EVIDENCE_LENGTH) + "…";
                }
                result.put(label, new Suggestion(name, confidence, "LLM", evidence));
            }
        } catch (java.io.IOException e) {
            log.warn("Antwort des LLM fuer Namensvorschlaege ist kein JSON: {}",
                    content.length() > 200 ? content.substring(0, 200) + "..." : content);
        }
        return result;
    }

    private static Map<String, Long> talkTime(List<TranscriptAssembler.Entry> entries) {
        Map<String, Long> time = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            TranscriptAssembler.Entry e = entries.get(i);
            if (e.speaker() == null) continue;
            long next = i + 1 < entries.size() ? entries.get(i + 1).startSeconds() : e.startSeconds() + 5;
            time.merge(e.speaker(), Math.max(1, Math.min(next - e.startSeconds(), 30)), Long::sum);
        }
        return time;
    }

    /** Namen aus dem Teilnehmerprotokoll des Bots ("  - Name", "JOINED: Name"). */
    static List<String> participantNames(String participantsLog) {
        Set<String> names = new LinkedHashSet<>();
        if (participantsLog == null) return new ArrayList<>();
        for (String line : participantsLog.split("\\R")) {
            String l = line.strip();
            if (l.startsWith("- ")) {
                names.add(l.substring(2).trim());
            } else if (l.contains("JOINED: ")) {
                names.add(l.substring(l.indexOf("JOINED: ") + 8).trim());
            }
        }
        names.removeIf(String::isEmpty);
        return new ArrayList<>(names);
    }

    private static String cutAtLine(String text, int maxChars) {
        int cut = text.lastIndexOf('\n', maxChars);
        return text.substring(0, cut > 0 ? cut : maxChars);
    }
}
