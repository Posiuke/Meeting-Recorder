package bbbbot.llm;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.Summary;
import bbbbot.recording.ParticipantService;
import bbbbot.repository.Repositories.SummaryRepo;
import bbbbot.settings.SettingsService;
import bbbbot.stt.TranscriptAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Erstellt die Meeting-Zusammenfassung aus Transkript, Teilnehmer-Protokoll und
 * Chat (Map-Reduce ueber Kontext-Chunks, Portierung von src/summary.ts).
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final LlmClient llm;
    private final SettingsService settings;
    private final SummaryRepo summaryRepo;
    private final ParticipantService participantService;

    public SummaryService(LlmClient llm, SettingsService settings, SummaryRepo summaryRepo,
                          ParticipantService participantService) {
        this.llm = llm;
        this.settings = settings;
        this.summaryRepo = summaryRepo;
        this.participantService = participantService;
    }

    /** Baut den Auswertungs-Kontext aus allen Quellen der Aufnahme. */
    public String buildContext(Recording recording, List<RecordingSegment> segments) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Meeting-Sitzung\n");
        sb.append("Sitzung: ").append(recording.getId()).append('\n');
        sb.append("Beginn (UTC): ").append(recording.getStartedAt()).append('\n');
        if (recording.getEndedAt() != null) {
            sb.append("Ende (UTC): ").append(recording.getEndedAt()).append('\n');
        }
        if (recording.getDurationMs() != null) {
            sb.append("Audiodauer: ").append(recording.getDurationMs() / 60000).append(" Minuten\n");
        }
        if (recording.getParticipantsLog() != null && !recording.getParticipantsLog().isBlank()) {
            sb.append("\n# Teilnehmer und Ereignisse\n").append(recording.getParticipantsLog()).append('\n');
        }
        // Zusammengefuehrtes Transkript mit fortlaufenden Zeitstempeln ueber alle
        // Segmente; Diarisierungs-Labels werden durch die Teilnehmernamen ersetzt.
        // Bevorzugt die geglaettete Fassung - genau dafuer gibt es sie.
        String transcript = TranscriptAssembler.toText(
                TranscriptAssembler.applyNames(
                        TranscriptAssembler.assemble(segments, true),
                        participantService.nameMap(recording.getId())));
        if (!transcript.isBlank()) {
            sb.append("\n# Audio-Transkript\n").append(transcript).append('\n');
        }
        if (recording.getChatLog() != null && !recording.getChatLog().isBlank()) {
            sb.append("\n# Oeffentlicher Chat (ab Aufnahmestart)\n").append(recording.getChatLog()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Erzeugt die Zusammenfassung: pro Kontext-Chunk eine Teil-Zusammenfassung,
     * bei mehreren Chunks ein konsolidierender Merge-Aufruf.
     */
    public Summary summarize(Recording recording, List<RecordingSegment> segments) {
        Summary summary = Summary.create(recording.getId());
        summary.setStatus(Summary.Status.RUNNING);
        summary.setModel(settings.get(SettingsService.LLM_MODEL));
        summaryRepo.save(summary);

        // Pro-Aufnahme-Einstellungen gehen vor den Admin-Standards (null/leer = Standard)
        String systemPrompt = recording.getSummaryPrompt() != null && !recording.getSummaryPrompt().isBlank()
                ? recording.getSummaryPrompt().trim()
                : settings.get(SettingsService.SUMMARY_SYSTEM_PROMPT);
        String language = recording.getSummaryLanguage() != null && !recording.getSummaryLanguage().isBlank()
                ? recording.getSummaryLanguage().trim()
                : settings.get(SettingsService.SUMMARY_LANGUAGE);
        Integer maxWords = recording.getSummaryMaxWords();
        // Laengenvorgabe nur im finalen Aufruf: Teil-Zusammenfassungen sollen
        // detailliert bleiben, gekuerzt wird erst beim Konsolidieren.
        String lengthInstruction = maxWords == null ? ""
                : "Die gesamte Zusammenfassung darf hoechstens " + maxWords + " Woerter umfassen. "
                + "Kuerze notfalls weniger wichtige Abschnitte.\n\n";
        int chunkChars = settings.getInt(SettingsService.SUMMARY_CHUNK_CHARS);

        String context = buildContext(recording, segments);
        List<String> chunks = TextChunker.chunk(context, chunkChars);
        log.info("Zusammenfassung fuer {}: {} Kontext-Zeichen in {} Chunk(s)",
                recording.getId(), context.length(), chunks.size());

        try {
            StringBuilder partials = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                String userPrompt = "Sprache der Zusammenfassung: " + language + "\n\n"
                        + (chunks.size() > 1
                        ? "Dies ist Teil %d von %d des Aufnahme-Kontexts. Fasse diesen Teil zusammen.\n\n".formatted(i + 1, chunks.size())
                        : "Fasse die folgende Aufnahme zusammen.\n\n" + lengthInstruction)
                        + chunks.get(i);
                LlmClient.LlmResult result = llm.chat(systemPrompt, userPrompt);
                if (!result.success()) {
                    return fail(summary, "Chunk " + (i + 1) + "/" + chunks.size() + ": " + result.error());
                }
                partials.append("--- Teil ").append(i + 1).append(" ---\n").append(result.content()).append("\n\n");
            }

            String markdown;
            if (chunks.size() == 1) {
                markdown = partials.toString().replaceFirst("(?s)^--- Teil 1 ---\n", "").trim();
            } else {
                String mergePrompt = "Sprache der Zusammenfassung: " + language + "\n\n"
                        + "Die folgenden Teil-Zusammenfassungen stammen aus EINER Aufnahme. "
                        + "Konsolidiere sie zu einer einzigen, konsistenten Zusammenfassung "
                        + "mit der vorgegebenen Struktur. Entferne Redundanzen.\n\n"
                        + lengthInstruction + partials;
                LlmClient.LlmResult merged = llm.chat(systemPrompt, mergePrompt);
                if (!merged.success()) {
                    return fail(summary, "Merge: " + merged.error());
                }
                markdown = merged.content();
            }

            String document = buildDocument(recording, markdown);
            summary.setMarkdown(document);
            summary.setStatus(Summary.Status.DONE);
            summary.setFinishedAt(Instant.now());
            summaryRepo.save(summary);
            writeSummaryFile(recording, document);
            log.info("Zusammenfassung fuer {} fertig ({} Zeichen).", recording.getId(), document.length());
            return summary;
        } catch (RuntimeException e) {
            return fail(summary, "Unerwarteter Fehler: " + e.getMessage());
        }
    }

    private Summary fail(Summary summary, String error) {
        log.error("Zusammenfassung fehlgeschlagen: {}", error);
        summary.setStatus(Summary.Status.FAILED);
        summary.setError(error);
        summary.setFinishedAt(Instant.now());
        summaryRepo.save(summary);
        return summary;
    }

    private String buildDocument(Recording recording, String aiSummary) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
        return "# Meeting-Zusammenfassung\n\n"
                + "**Sitzung:** " + recording.getId() + "\n"
                + "**Beginn (UTC):** " + fmt.format(recording.getStartedAt()) + "\n"
                + "**Erstellt (UTC):** " + fmt.format(Instant.now()) + "\n\n"
                + "---\n\n"
                + aiSummary.trim() + "\n";
    }

    /**
     * Gleicht summary.md mit der Datenbank ab: Die Datei spiegelt immer die
     * neueste FERTIGE Zusammenfassung wider; gibt es keine mehr, wird sie
     * geloescht. Nach jeder Mutation (Bearbeiten, Loeschen) aufrufen, damit
     * Datei und API nicht auseinanderlaufen.
     */
    public void syncSummaryFile(Recording recording) {
        Summary latestDone = summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId()).stream()
                .filter(s -> s.getStatus() == Summary.Status.DONE && s.getMarkdown() != null)
                .findFirst()
                .orElse(null);
        if (latestDone != null) {
            writeSummaryFile(recording, latestDone.getMarkdown());
            return;
        }
        try {
            Files.deleteIfExists(Path.of(recording.getDirectory()).resolve("summary.md"));
        } catch (IOException e) {
            log.warn("summary.md konnte nicht geloescht werden: {}", e.getMessage());
        }
    }

    private void writeSummaryFile(Recording recording, String document) {
        try {
            Path dir = Path.of(recording.getDirectory());
            if (Files.isDirectory(dir)) {
                Files.writeString(dir.resolve("summary.md"), document, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("summary.md konnte nicht geschrieben werden: {}", e.getMessage());
        }
    }
}
