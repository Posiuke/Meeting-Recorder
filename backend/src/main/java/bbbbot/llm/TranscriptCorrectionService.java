package bbbbot.llm;

import bbbbot.domain.RecordingSegment;
import bbbbot.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glaettet ein Whisper-Rohtranskript per LLM: Fuellwoerter und Wiederholungen
 * raus, Satzzeichen und Gross-/Kleinschreibung richtig, offensichtliche
 * Erkennungsfehler berichtigt - besonders bei Fachbegriffen, die aus dem
 * persoenlichen Glossar des Aufnahme-Besitzers kommen.
 *
 * <p><b>Einheit der Glaettung ist der ganze Satz.</b> Whisper liefert
 * Zeitstempel-Zeilen, die haeufig mitten im Satz enden ("und dann haben wir" /
 * "das Thema verschoben"). Ein solches Fragment isoliert zu glaetten kann nicht
 * gut werden - deshalb werden aufeinanderfolgende Zeilen zu Saetzen
 * zusammengefasst, und erst die gehen ans Modell. Ein Satz wird auch nie ueber
 * zwei Schritte zerschnitten. Der geglaettete Satz erhaelt den Zeitstempel
 * seiner ersten Zeile; die Folgezeilen entfallen in der geglaetteten Fassung.
 * Ein Sprecherwechsel beendet immer einen Satz.
 *
 * <p><b>Warum nummerierte Einheiten:</b> Zeitstempel ({@code [12:34]}) und
 * Sprecher-Labels ({@code SPEAKER_00:}) tragen die gesamte Struktur des
 * Transkripts - ohne sie kann {@link bbbbot.stt.TranscriptAssembler} nichts mehr
 * zusammensetzen. Ein LLM, das das komplette Transkript umschreibt, verliert oder
 * verschiebt so etwas zuverlaessig. Deshalb bekommt das Modell <b>nur die
 * Textteile</b>, versehen mit einer Nummer, und die Struktur wird hier
 * anschliessend wieder davor gesetzt. Was in der Antwort fehlt oder unbrauchbar
 * ist, faellt einheitenweise auf das Original zurueck - die Struktur kann dabei
 * grundsaetzlich nicht beschaedigt werden.
 */
@Service
public class TranscriptCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptCorrectionService.class);

    /** [mm:ss] / [hh:mm:ss] am Zeilenanfang - wie in TranscriptAssembler. */
    private static final Pattern TIMESTAMP_LINE =
            Pattern.compile("^(\\[\\d{1,3}:\\d{2}(?::\\d{2})?]\\s*)(.*)$");

    /** Sprecher-Kopfzeile der Diarisierung, z.B. "SPEAKER_00:" - reine Struktur. */
    private static final Pattern SPEAKER_LINE = Pattern.compile("^[^\\[\\]]{1,80}:$");

    /** Antwortzeile des Modells: "12 | korrigierter Satz". */
    private static final Pattern ANSWER_LINE = Pattern.compile("^\\s*(\\d{1,5})\\s*\\|\\s?(.*)$");

    /** Satzende: Schlusszeichen, ggf. gefolgt von schliessenden Anfuehrungen/Klammern. */
    private static final Pattern SENTENCE_END = Pattern.compile(".*[.!?…]['\"»”’)\\]]*$");

    /**
     * Ein geglaetteter Satz darf nicht beliebig lang werden. Wird er es, hat das
     * Modell erklaert statt korrigiert - dann bleibt das Original stehen.
     */
    private static final int MAX_GROWTH_FACTOR = 3;

    private final LlmClient llm;
    private final SettingsService settings;
    private final GlossaryService glossaryService;

    public TranscriptCorrectionService(LlmClient llm, SettingsService settings,
                                       GlossaryService glossaryService) {
        this.llm = llm;
        this.settings = settings;
        this.glossaryService = glossaryService;
    }

    /**
     * Ergebnis einer Glaettung.
     *
     * @param correctedSentences Saetze, die das Modell erfolgreich geglaettet hat
     * @param keptSentences      Saetze, die (als Rueckfall) im Original geblieben sind
     * @param steps              durchgefuehrte LLM-Aufrufe (Glaettungsschritte)
     * @param plannedSteps       geplante Schritte - kleiner als {@code steps} heisst abgebrochen
     * @param slowestStepMs      langsamster Schritt; macht ein zaehes Modell im Log sichtbar
     * @param llmUnavailable     das Modell hat nicht geantwortet (Timeout/nicht erreichbar).
     *                           Dann lohnt es nicht, weitere Segmente zu versuchen.
     */
    public record CorrectionResult(boolean success, String text, int correctedSentences,
                                   int keptSentences, int steps, int plannedSteps,
                                   long totalMs, long slowestStepMs, boolean llmUnavailable,
                                   String error) {
        static CorrectionResult failed(String error) {
            return new CorrectionResult(false, null, 0, 0, 0, 0, 0, 0, false, error);
        }
    }

    public boolean isEnabled() {
        return settings.getBool(SettingsService.CORRECTION_ENABLED);
    }

    /** Glossar-Block des Nutzers, der allen Aufrufen fuer dessen Aufnahmen mitgegeben wird. */
    public String glossaryFor(java.util.UUID ownerId) {
        return glossaryService.promptBlock(ownerId);
    }

    /**
     * Glaettet den Text EINES Segments. Der Aufrufer speichert das Ergebnis; bei
     * {@code success == false} bleibt das Original die einzige Fassung.
     */
    public CorrectionResult correct(RecordingSegment segment, String glossaryBlock) {
        String original = segment.getTranscriptText();
        if (original == null || original.isBlank()) {
            return CorrectionResult.failed("Kein Transkript vorhanden");
        }

        int maxSentenceChars = Math.max(80, settings.getInt(SettingsService.CORRECTION_MAX_SENTENCE_CHARS));
        int chunkChars = Math.max(500, settings.getInt(SettingsService.CORRECTION_CHUNK_CHARS));
        String systemPrompt = settings.get(SettingsService.CORRECTION_SYSTEM_PROMPT);

        List<Line> lines = split(original);
        List<Unit> units = groupIntoSentences(lines, maxSentenceChars);
        if (units.isEmpty()) {
            return CorrectionResult.failed("Keine Textzeilen zum Glaetten");
        }

        List<List<Unit>> steps = chunk(units, chunkChars);
        int corrected = 0;
        int kept = 0;
        int done = 0;
        long totalMs = 0;
        long slowestMs = 0;
        boolean llmUnavailable = false;
        String lastError = null;

        for (int index = 0; index < steps.size(); index++) {
            List<Unit> step = steps.get(index);
            done++;
            long begin = System.nanoTime();
            StepResult result = correctStep(step, systemPrompt, glossaryBlock);
            long ms = (System.nanoTime() - begin) / 1_000_000;
            totalMs += ms;
            slowestMs = Math.max(slowestMs, ms);

            if (result.replacements() == null) {
                lastError = result.error();
                kept += step.size();
                if (result.llmUnavailable()) {
                    // Nicht weiter gegen ein stummes Modell laufen: Bei 300 s
                    // Timeout und zwei Versuchen kostet JEDER Block gut 10 Minuten.
                    // Ueber alle Bloecke und Segmente einer Stunde Audio blockiert
                    // das die Warteschlange stundenlang - und am Ende steht trotzdem
                    // ueberall das Original.
                    llmUnavailable = true;
                    for (int rest = index + 1; rest < steps.size(); rest++) {
                        kept += steps.get(rest).size();
                    }
                    log.warn("Glaettung nach Schritt {}/{} abgebrochen - das Modell antwortet nicht: {}",
                            done, steps.size(), lastError);
                    break;
                }
                continue;
            }
            for (int position = 0; position < step.size(); position++) {
                Unit unit = step.get(position);
                String replacement = result.replacements().get(position + 1);
                if (replacement == null) {
                    kept++;
                    continue;
                }
                apply(lines, unit, replacement);
                corrected++;
            }
        }

        if (corrected == 0) {
            return new CorrectionResult(false, null, 0, kept, done, steps.size(), totalMs, slowestMs,
                    llmUnavailable,
                    lastError == null ? "Das Modell hat keinen Satz geglaettet" : lastError);
        }
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            if (line.dropped) continue;
            if (text.length() > 0) text.append('\n');
            text.append(line.render());
        }
        return new CorrectionResult(true, text.toString(), corrected, kept, done, steps.size(),
                totalMs, slowestMs, llmUnavailable, lastError);
    }

    /**
     * Geglaetteten Satz einsetzen: Er uebernimmt die Struktur (Zeitstempel) der
     * ERSTEN Zeile der Einheit, die Folgezeilen entfallen in der geglaetteten Fassung.
     */
    private static void apply(List<Line> lines, Unit unit, String correctedSentence) {
        lines.get(unit.lineIndexes().get(0)).payload = correctedSentence;
        for (int i = 1; i < unit.lineIndexes().size(); i++) {
            lines.get(unit.lineIndexes().get(i)).dropped = true;
        }
    }

    /**
     * Ergebnis eines Glaettungsschritts.
     *
     * @param replacements   Ersetzungen je Position im Block (1-basiert);
     *                       {@code null} = unbrauchbar, das Original bleibt
     * @param llmUnavailable der Aufruf selbst ist gescheitert (Timeout, Netz, HTTP-Fehler,
     *                       leere Antwort) - im Unterschied zu einer inhaltlich
     *                       unbrauchbaren Antwort. Solche Fehler wiederholen sich bei
     *                       jedem weiteren Block, deshalb wird dann abgebrochen.
     */
    private record StepResult(Map<Integer, String> replacements, String error,
                              boolean llmUnavailable) {
        static StepResult unusable(String error) {
            return new StepResult(null, error, false);
        }

        static StepResult llmFailed(String error) {
            return new StepResult(null, error, true);
        }
    }

    /**
     * Ein Glaettungsschritt: schickt die Saetze eines Blocks ans Modell und
     * liefert die Ersetzungen je Position im Block (1-basiert).
     */
    private StepResult correctStep(List<Unit> step, String systemPrompt, String glossaryBlock) {
        StringBuilder numbered = new StringBuilder();
        for (int position = 0; position < step.size(); position++) {
            numbered.append(position + 1).append(" | ").append(step.get(position).text()).append('\n');
        }

        StringBuilder userPrompt = new StringBuilder();
        if (glossaryBlock != null && !glossaryBlock.isBlank()) {
            userPrompt.append("Bekannte Begriffe und Abkuerzungen (richtige Schreibweise verwenden):\n")
                    .append(glossaryBlock).append('\n');
        }
        userPrompt.append("Glaette die folgenden ").append(step.size())
                .append(" Saetze. Gib GENAU ").append(step.size())
                .append(" Zeilen im Format \"Nummer | Satz\" zurueck - dieselben Nummern, ")
                .append("dieselbe Reihenfolge, keine zusaetzlichen Erklaerungen.\n\n")
                .append(numbered);

        // Budget aus der TATSAECHLICHEN Blockgroesse: Die Antwort ist etwa so lang
        // wie die Eingabe. Das Glossar zaehlt nicht mit - es geht in die Frage ein,
        // nicht in die Antwort.
        int answerTokens = numbered.length() / 2 + 512;
        // Darf das Modell "nachdenken", laeuft das im selben Budget wie die Antwort -
        // dann reicht die Schaetzung nicht und das Modell liefert eine leere Antwort.
        // Reserve dafuer: das Admin-Budget.
        int maxTokens = settings.getBool(SettingsService.LLM_DISABLE_THINKING)
                ? answerTokens
                : answerTokens + settings.getInt(SettingsService.LLM_MAX_TOKENS);
        LlmClient.LlmResult result = llm.chat(systemPrompt, userPrompt.toString(), maxTokens);
        if (!result.success()) {
            log.warn("Glaettungsschritt fehlgeschlagen: {}", result.error());
            return StepResult.llmFailed(result.error());
        }
        Map<Integer, String> answered = parseAnswer(result.content());
        if (answered.isEmpty()) {
            log.warn("Glaettungsschritt: Antwort ohne verwertbare Zeilen im Format \"Nummer | Satz\"");
            return StepResult.unusable("Antwort des Modells ohne Zeilen im Format \"Nummer | Satz\"");
        }

        Map<Integer, String> accepted = new HashMap<>();
        for (Map.Entry<Integer, String> entry : answered.entrySet()) {
            int position = entry.getKey();
            if (position < 1 || position > step.size()) continue;
            String candidate = entry.getValue().strip();
            if (!acceptable(step.get(position - 1).text(), candidate)) continue;
            accepted.put(position, candidate);
        }
        return accepted.isEmpty()
                ? StepResult.unusable("Kein Satz der Antwort war brauchbar")
                : new StepResult(accepted, null, false);
    }

    /** Zeilen der Modellantwort im Format "Nummer | Text" einsammeln. */
    static Map<Integer, String> parseAnswer(String answer) {
        Map<Integer, String> result = new HashMap<>();
        if (answer == null) return result;
        for (String line : answer.split("\\R")) {
            Matcher m = ANSWER_LINE.matcher(line);
            if (!m.matches()) continue;
            result.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        return result;
    }

    /**
     * Plausibilitaetspruefung eines geglaetteten Satzes: nicht leer und nicht
     * unverhaeltnismaessig laenger als das Original (dann hat das Modell
     * kommentiert oder halluziniert).
     */
    static boolean acceptable(String original, String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        return candidate.length() <= Math.max(40, original.length() * MAX_GROWTH_FACTOR);
    }

    /**
     * Fasst aufeinanderfolgende Textzeilen zu Saetzen zusammen. Grenzen sind ein
     * Satzschlusszeichen, jede Strukturzeile (Sprecherwechsel, Leerzeile) und -
     * als Notbremse fuer Transkripte ohne Satzzeichen - {@code maxSentenceChars}.
     */
    static List<Unit> groupIntoSentences(List<Line> lines, int maxSentenceChars) {
        List<Unit> units = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (!line.hasPayload()) {
                // Sprecherwechsel oder Leerzeile: hier endet der Satz auf jeden Fall
                flush(units, current, text);
                continue;
            }
            String payload = line.payload.strip();
            if (!current.isEmpty()) text.append(' ');
            current.add(i);
            text.append(payload);
            if (SENTENCE_END.matcher(payload).matches() || text.length() >= maxSentenceChars) {
                flush(units, current, text);
            }
        }
        flush(units, current, text);
        return units;
    }

    private static void flush(List<Unit> units, List<Integer> current, StringBuilder text) {
        if (current.isEmpty()) return;
        units.add(new Unit(List.copyOf(current), text.toString()));
        current.clear();
        text.setLength(0);
    }

    /**
     * Verteilt die Saetze auf Glaettungsschritte, die je unter der Zeichengrenze
     * bleiben. Ein Satz wird nie zerschnitten - notfalls ist ein Schritt eben
     * laenger als die Grenze.
     */
    static List<List<Unit>> chunk(List<Unit> units, int chunkChars) {
        List<List<Unit>> chunks = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        int size = 0;
        for (Unit unit : units) {
            int length = unit.text().length() + 8; // Nummer + Trennzeichen
            if (!current.isEmpty() && size + length > chunkChars) {
                chunks.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(unit);
            size += length;
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }

    /**
     * Zerlegt das Transkript in Struktur (Zeitstempel, Sprecherzeilen) und
     * Textteile. Nur die Textteile gehen ans Modell.
     */
    static List<Line> split(String transcript) {
        List<Line> lines = new ArrayList<>();
        for (String raw : transcript.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                lines.add(new Line("", null));
                continue;
            }
            Matcher ts = TIMESTAMP_LINE.matcher(line);
            if (ts.matches()) {
                String content = ts.group(2).strip();
                lines.add(content.isEmpty()
                        ? new Line(line, null)
                        : new Line(ts.group(1), content));
                continue;
            }
            if (SPEAKER_LINE.matcher(line).matches()) {
                lines.add(new Line(line, null));
                continue;
            }
            lines.add(new Line("", line));
        }
        return lines;
    }

    /**
     * Ein Satz als Glaettungseinheit: die beteiligten Zeilen (mindestens eine)
     * und ihr zusammengefuegter Originaltext.
     */
    record Unit(List<Integer> lineIndexes, String text) {}

    /** Eine Transkriptzeile: unveraenderliche Struktur plus optionaler Textteil. */
    static final class Line {
        final String prefix;
        String payload;
        /** true = geht in der geglaetteten Fassung im Satz der Vorgaengerzeile auf. */
        boolean dropped;

        Line(String prefix, String payload) {
            this.prefix = prefix;
            this.payload = payload;
        }

        boolean hasPayload() {
            return payload != null && !payload.isBlank();
        }

        String render() {
            return payload == null ? prefix : prefix + payload;
        }
    }
}
