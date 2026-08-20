package bbbbot.llm;

import bbbbot.docs.RecordingDocumentService;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Erstellt die Meeting-Zusammenfassung aus Transkript, Teilnehmer-Protokoll und
 * Chat (Map-Reduce ueber Kontext-Chunks, Portierung von src/summary.ts).
 *
 * <p>Jede Auswertung legt eine weitere {@link Summary Fassung} an und macht sie
 * nach Erfolg zur aktuellen; die vorherigen bleiben stehen. Ueberschrieben wird
 * nichts - siehe {@link #makeCurrent(Recording, Summary)}.
 *
 * <p>Der Aufnahme beigefuegte Unterlagen (Tagesordnung, Folien, Papiere) gehen als
 * eigener Abschnitt in jeden Aufruf ein - siehe
 * {@link RecordingDocumentService#promptBlock(java.util.UUID)}.
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final LlmClient llm;
    private final SettingsService settings;
    private final SummaryRepo summaryRepo;
    private final ParticipantService participantService;
    private final RecordingDocumentService documentService;

    public SummaryService(LlmClient llm, SettingsService settings, SummaryRepo summaryRepo,
                          ParticipantService participantService,
                          RecordingDocumentService documentService) {
        this.llm = llm;
        this.settings = settings;
        this.summaryRepo = summaryRepo;
        this.participantService = participantService;
        this.documentService = documentService;
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
        // Pro-Aufnahme-Einstellungen gehen vor den Admin-Standards (null/leer = Standard)
        String systemPrompt = recording.getSummaryPrompt() != null && !recording.getSummaryPrompt().isBlank()
                ? recording.getSummaryPrompt().trim()
                : settings.get(SettingsService.SUMMARY_SYSTEM_PROMPT);
        String language = recording.getSummaryLanguage() != null && !recording.getSummaryLanguage().isBlank()
                ? recording.getSummaryLanguage().trim()
                : settings.get(SettingsService.SUMMARY_LANGUAGE);

        // Modell und Temperatur der Aufnahme gehen vor den Admin-Vorgaben. Sie
        // stammen in der Regel aus der gewaehlten Vorlage - so laesst sich
        // dasselbe Transkript mit zwei Modellen auswerten und vergleichen.
        String model = recording.getSummaryModel() != null && !recording.getSummaryModel().isBlank()
                ? recording.getSummaryModel().trim()
                : settings.get(SettingsService.LLM_MODEL);
        Double temperature = recording.getSummaryTemperature() != null
                ? recording.getSummaryTemperature()
                : settings.getDouble(SettingsService.LLM_TEMPERATURE);
        LlmClient.Overrides overrides = LlmClient.Overrides.modelAndTemperature(model, temperature);

        // Die Herkunft wird an der Fassung festgehalten, nicht nur an der
        // Aufnahme: Nur so ist spaeter zu sehen, womit DIESE Fassung entstanden ist.
        Summary summary = Summary.create(recording.getId());
        summary.setStatus(Summary.Status.RUNNING);
        summary.setModel(model);
        summary.setTemperature(temperature);
        summary.setTemplateName(recording.getSummaryTemplateName());
        summary.setSystemPrompt(systemPrompt);
        summaryRepo.save(summary);

        Integer maxWords = recording.getSummaryMaxWords();
        // Laengenvorgabe nur im finalen Aufruf: Teil-Zusammenfassungen sollen
        // detailliert bleiben, gekuerzt wird erst beim Konsolidieren.
        String lengthInstruction = maxWords == null ? ""
                : "Die gesamte Zusammenfassung darf hoechstens " + maxWords + " Woerter umfassen. "
                + "Kuerze notfalls weniger wichtige Abschnitte.\n\n";
        int chunkChars = settings.getInt(SettingsService.SUMMARY_CHUNK_CHARS);

        String context = buildContext(recording, segments);
        List<String> chunks = TextChunker.chunk(context, chunkChars);
        // Beigefuegte Unterlagen gehen in JEDEN Aufruf ein, nicht als Teil des
        // Kontexts: Der wird in Bloecke geschnitten, und das Thema muss in jedem
        // Block bekannt sein - auch beim Konsolidieren am Ende.
        String documents = documentService.promptBlock(recording.getId());
        log.info("Zusammenfassung fuer {}: {} Kontext-Zeichen in {} Chunk(s), {} Zeichen Unterlagen, "
                        + "Modell {} (T={})",
                recording.getId(), context.length(), chunks.size(), documents.length(),
                model, temperature);

        try {
            StringBuilder partials = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                String userPrompt = "Sprache der Zusammenfassung: " + language + "\n\n"
                        + documents
                        + (chunks.size() > 1
                        ? "Dies ist Teil %d von %d des Aufnahme-Kontexts. Fasse diesen Teil zusammen.\n\n".formatted(i + 1, chunks.size())
                        : "Fasse die folgende Aufnahme zusammen.\n\n" + lengthInstruction)
                        + chunks.get(i);
                LlmClient.LlmResult result = llm.chat(systemPrompt, userPrompt, overrides);
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
                        + documents
                        + "Die folgenden Teil-Zusammenfassungen stammen aus EINER Aufnahme. "
                        + "Konsolidiere sie zu einer einzigen, konsistenten Zusammenfassung "
                        + "mit der vorgegebenen Struktur. Entferne Redundanzen.\n\n"
                        + lengthInstruction + partials;
                LlmClient.LlmResult merged = llm.chat(systemPrompt, mergePrompt, overrides);
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
            // Die neue Fassung wird die aktuelle; die vorherige bleibt daneben stehen.
            makeCurrent(recording, summary);
            log.info("Zusammenfassung fuer {} fertig ({} Zeichen, Fassung {}).",
                    recording.getId(), document.length(),
                    summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId()).size());
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

    /** Die aktuelle Fassung der Aufnahme, falls es eine gibt. */
    public Optional<Summary> current(UUID recordingId) {
        return summaryRepo.findByRecordingIdAndCurrentIsTrue(recordingId);
    }

    /**
     * Macht diese Fassung zur aktuellen und schreibt sie nach summary.md. Die
     * bisherige verliert nur ihre Markierung - geloescht wird sie nicht.
     *
     * <p>Die alte Markierung wird vor der neuen gespeichert und geleert
     * ({@code saveAndFlush}), weil der Teil-Index {@code uq_summary_current}
     * genau eine aktuelle Fassung je Aufnahme zulaesst.
     */
    public void makeCurrent(Recording recording, Summary summary) {
        if (!summary.isUsable()) {
            throw new IllegalArgumentException("Nur eine fertige Fassung mit Inhalt kann die aktuelle sein");
        }
        summaryRepo.findByRecordingIdAndCurrentIsTrue(recording.getId())
                .filter(previous -> !previous.getId().equals(summary.getId()))
                .ifPresent(previous -> {
                    previous.setCurrent(false);
                    summaryRepo.saveAndFlush(previous);
                });
        if (!summary.isCurrent()) {
            summary.setCurrent(true);
            summaryRepo.save(summary);
        }
        writeSummaryFile(recording, summary.getMarkdown());
    }

    /**
     * Gleicht aktuelle Fassung und summary.md mit der Datenbank ab: Ist keine
     * Fassung mehr aktuell - weil die aktuelle geloescht wurde -, uebernimmt die
     * neueste brauchbare; gibt es keine mehr, verschwindet summary.md. Nach jeder
     * Mutation (Bearbeiten, Loeschen) aufrufen, damit Datei, API und Anzeige nicht
     * auseinanderlaufen.
     */
    public void syncCurrent(Recording recording) {
        Optional<Summary> current = summaryRepo.findByRecordingIdAndCurrentIsTrue(recording.getId());
        if (current.isEmpty()) {
            current = summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId()).stream()
                    .filter(Summary::isUsable)
                    .findFirst();
            current.ifPresent(s -> {
                s.setCurrent(true);
                summaryRepo.save(s);
            });
        }
        if (current.isPresent()) {
            writeSummaryFile(recording, current.get().getMarkdown());
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
