package bbbbot.llm;

import bbbbot.domain.GlossaryEntry;
import bbbbot.repository.Repositories.GlossaryEntryRepo;
import bbbbot.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persoenliches Glossar: Abkuerzungen und Fachbegriffe, die in den eigenen
 * Besprechungen vorkommen. Die Eintraege gehen in die Glaettung des Transkripts
 * ein - damit schreibt die KI haus- und fachinterne Begriffe richtig, statt sie
 * durch aehnlich klingende Alltagswoerter zu ersetzen.
 */
@Service
public class GlossaryService {

    /**
     * Reine Missbrauchsgrenze - fachlich soll das Glossar so lang sein duerfen,
     * wie der Nutzer es braucht. Wie viel davon im Prompt landet, steuert der
     * Admin ueber {@code correction.glossaryMaxChars}.
     */
    public static final int MAX_ENTRIES_PER_USER = 10_000;

    private final GlossaryEntryRepo repo;
    private final SettingsService settings;

    public GlossaryService(GlossaryEntryRepo repo, SettingsService settings) {
        this.repo = repo;
        this.settings = settings;
    }

    public List<GlossaryEntry> entriesOf(UUID ownerId) {
        return repo.findByOwnerIdOrderByTermKeyAsc(ownerId);
    }

    /**
     * Ergebnis eines Imports - wird unveraendert als JSON ans Frontend gegeben.
     *
     * @param created   neu angelegte Begriffe
     * @param updated   vorhandene Begriffe mit neuer Bedeutung oder Schreibweise
     * @param unchanged Begriffe, die schon genau so im Glossar standen
     * @param skipped   Zeilen der Datei, die nicht uebernommen wurden
     * @param warnings  Hinweise dazu (mit Zeilennummer), gekuerzt auf {@link #MAX_WARNINGS}
     */
    public record ImportResult(int created, int updated, int unchanged, int skipped,
                               List<String> warnings) {
    }

    /** Mehr Hinweise liest niemand - und die Antwort soll klein bleiben. */
    static final int MAX_WARNINGS = 50;

    /**
     * Uebernimmt eine CSV-Datei ins Glossar des Nutzers: vorhandene Begriffe
     * werden mit der Bedeutung aus der Datei aktualisiert, neue angelegt, nicht
     * genannte bleiben unberuehrt. Es wird nie etwas geloescht - ein Import darf
     * kein Glossar zerstoeren, das ueber Monate gewachsen ist.
     *
     * <p>Verglichen wird ueber die Vergleichsform des Begriffs
     * ({@link GlossaryEntry#normalizeKey}), damit "RZ" und "rz" derselbe
     * Eintrag sind; die Schreibweise aus der Datei gewinnt.
     */
    @Transactional
    public ImportResult importCsv(UUID ownerId, byte[] content) {
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse(GlossaryCsv.decode(content));
        List<String> warnings = new ArrayList<>(parsed.notes());
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = parsed.skipped();
        long total = repo.countByOwnerId(ownerId);
        boolean limitReached = false;

        for (GlossaryCsv.Row row : parsed.rows()) {
            if (row.term().length() > GlossaryEntry.MAX_TERM_LENGTH) {
                skipped++;
                warnings.add("Zeile " + row.line() + ": Begriff ist zu lang (max. "
                        + GlossaryEntry.MAX_TERM_LENGTH + " Zeichen)");
                continue;
            }
            String meaning = row.meaning();
            if (meaning != null && meaning.length() > GlossaryEntry.MAX_MEANING_LENGTH) {
                skipped++;
                warnings.add("Zeile " + row.line() + ": Bedeutung ist zu lang (max. "
                        + GlossaryEntry.MAX_MEANING_LENGTH + " Zeichen)");
                continue;
            }
            Optional<GlossaryEntry> existing =
                    repo.findByOwnerIdAndTermKey(ownerId, GlossaryEntry.normalizeKey(row.term()));
            if (existing.isPresent()) {
                GlossaryEntry entry = existing.get();
                if (row.term().equals(entry.getTerm()) && Objects.equals(meaning, entry.getMeaning())) {
                    unchanged++;
                    continue;
                }
                entry.setTerm(row.term());
                entry.setMeaning(meaning);
                entry.setUpdatedAt(Instant.now());
                repo.save(entry);
                updated++;
                continue;
            }
            if (total >= MAX_ENTRIES_PER_USER) {
                skipped++;
                limitReached = true;
                continue;
            }
            repo.save(GlossaryEntry.create(ownerId, row.term(), meaning));
            created++;
            total++;
        }
        if (limitReached) {
            warnings.add("Grenze von " + MAX_ENTRIES_PER_USER
                    + " Eintraegen erreicht - weitere Begriffe wurden nicht angelegt");
        }
        return new ImportResult(created, updated, unchanged, skipped, shorten(warnings));
    }

    private static List<String> shorten(List<String> warnings) {
        if (warnings.size() <= MAX_WARNINGS) return List.copyOf(warnings);
        List<String> shortened = new ArrayList<>(warnings.subList(0, MAX_WARNINGS));
        shortened.add("... und " + (warnings.size() - MAX_WARNINGS) + " weitere Hinweise");
        return List.copyOf(shortened);
    }

    /**
     * Baut den Glossar-Abschnitt fuer den Prompt. Leeres Glossar ergibt einen
     * leeren String (dann entfaellt der Abschnitt ganz). Wie viele Zeichen
     * hoechstens mitgehen, legt der Admin fest ({@code correction.glossaryMaxChars},
     * 0 = unbegrenzt) - der Block geht in JEDEN Glaettungsschritt ein und kostet
     * dort entsprechend Kontext.
     */
    public String promptBlock(UUID ownerId) {
        return renderPromptBlock(entriesOf(ownerId),
                settings.getInt(SettingsService.CORRECTION_GLOSSARY_MAX_CHARS));
    }

    /**
     * Rendert die Eintraege als Liste "Begriff = Bedeutung".
     *
     * @param maxChars Obergrenze in Zeichen; {@code <= 0} bedeutet unbegrenzt.
     *                 Abgeschnitten wird nur zwischen Eintraegen, nie mitten in einem.
     */
    static String renderPromptBlock(List<GlossaryEntry> entries, int maxChars) {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (GlossaryEntry entry : entries) {
            String term = entry.getTerm() == null ? "" : entry.getTerm().strip();
            if (term.isEmpty()) continue;
            String line = entry.getMeaning() == null || entry.getMeaning().isBlank()
                    ? "- " + term + "\n"
                    : "- " + term + " = " + entry.getMeaning().strip() + "\n";
            if (maxChars > 0 && sb.length() + line.length() > maxChars) break;
            sb.append(line);
        }
        return sb.toString();
    }
}
