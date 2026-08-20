package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.domain.GlossaryEntry;
import bbbbot.llm.GlossaryCsv;
import bbbbot.llm.GlossaryService;
import bbbbot.repository.Repositories.GlossaryEntryRepo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Glossar in zwei Geltungsbereichen:
 * <ul>
 *   <li><b>persoenlich</b> ({@code /api/glossary}) - jeder Nutzer pflegt seine
 *       eigenen Abkuerzungen und Fachbegriffe,</li>
 *   <li><b>gemeinsam</b> ({@code /api/glossary/shared}) - installationsweit, von
 *       Admins gepflegt. Lesen und Exportieren duerfen alle: Wer sieht, dass ein
 *       Begriff schon gemeinsam gepflegt wird, legt ihn nicht noch einmal selbst an.</li>
 * </ul>
 * Bei der KI-Glaettung gehen beide ein - das gemeinsame Glossar und das
 * persoenliche des Aufnahme-Besitzers, bei gleichem Begriff gewinnt das persoenliche.
 */
@RestController
@RequestMapping("/api/glossary")
public class GlossaryController {

    /**
     * Ein Glossar ist Text - mehr als ein paar Megabyte kann keine sinnvolle
     * Datei sein. Das globale Upload-Limit gilt fuer Meeting-Videos und waere
     * hier eine offene Tuer.
     */
    private static final long MAX_IMPORT_BYTES = 8L * 1024 * 1024;

    /** Steht fuer das gemeinsame Glossar der Installation (Eintraege ohne Besitzer). */
    private static final UUID SHARED = null;

    private final GlossaryEntryRepo repo;
    private final GlossaryService glossaryService;

    public GlossaryController(GlossaryEntryRepo repo, GlossaryService glossaryService) {
        this.repo = repo;
        this.glossaryService = glossaryService;
    }

    // -------------------------------------------------- persoenliches Glossar

    @GetMapping
    public List<Dtos.GlossaryEntryView> list() {
        return view(glossaryService.entriesOf(CurrentUser.get().getId()));
    }

    /**
     * Glossar als CSV-Datei ({@code Begriff;Bedeutung}) zum Herunterladen -
     * bearbeitbar in Excel oder jedem Texteditor und per {@code /import} wieder
     * einlesbar. Ein leeres Glossar liefert die Kopfzeile allein und dient damit
     * als Vorlage.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        AppUser user = CurrentUser.get();
        return csv(user.getId(), safeFilenamePart(user.getUsername()));
    }

    /**
     * Liest eine CSV-Datei ins eigene Glossar ein (zusammenfuehren, nie loeschen).
     * Das Ergebnis nennt Zahlen und Hinweise, damit sichtbar ist, was die Datei
     * bewirkt hat.
     */
    @PostMapping("/import")
    public GlossaryService.ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return importInto(CurrentUser.get().getId(), file);
    }

    @PostMapping
    public Dtos.GlossaryEntryView create(@RequestBody Dtos.GlossaryEntryRequest request) {
        return createIn(CurrentUser.get().getId(), request);
    }

    @PutMapping("/{id}")
    public Dtos.GlossaryEntryView update(@PathVariable UUID id,
                                         @RequestBody Dtos.GlossaryEntryRequest request) {
        return updateIn(CurrentUser.get().getId(), id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        repo.delete(requireEntry(CurrentUser.get().getId(), id));
    }

    // ----------------------------------------------------- gemeinsames Glossar

    @GetMapping("/shared")
    public List<Dtos.GlossaryEntryView> listShared() {
        return view(glossaryService.entriesOf(SHARED));
    }

    /** Gemeinsames Glossar als CSV - auch fuer Nicht-Admins, sie duerfen es lesen. */
    @GetMapping("/shared/export")
    public ResponseEntity<byte[]> exportShared() {
        return csv(SHARED, "gemeinsam");
    }

    @PostMapping("/shared/import")
    public GlossaryService.ImportResult importSharedCsv(@RequestParam("file") MultipartFile file) {
        requireAdmin();
        return importInto(SHARED, file);
    }

    @PostMapping("/shared")
    public Dtos.GlossaryEntryView createShared(@RequestBody Dtos.GlossaryEntryRequest request) {
        requireAdmin();
        return createIn(SHARED, request);
    }

    @PutMapping("/shared/{id}")
    public Dtos.GlossaryEntryView updateShared(@PathVariable UUID id,
                                               @RequestBody Dtos.GlossaryEntryRequest request) {
        requireAdmin();
        return updateIn(SHARED, id, request);
    }

    @DeleteMapping("/shared/{id}")
    public void deleteShared(@PathVariable UUID id) {
        requireAdmin();
        repo.delete(requireEntry(SHARED, id));
    }

    // ------------------------------------------------- gemeinsame Bearbeitung

    /**
     * Die eigentliche Arbeit fuer beide Geltungsbereiche. {@code ownerId} ist der
     * Nutzer, dessen Liste gemeint ist - {@code null} steht fuer das gemeinsame
     * Glossar. Damit gelten Pruefungen und Fehlermeldungen fuer beide gleich; ein
     * Eintrag kann nicht versehentlich in der anderen Liste landen.
     */
    private Dtos.GlossaryEntryView createIn(UUID ownerId, Dtos.GlossaryEntryRequest request) {
        String term = requireTerm(request.term());
        String meaning = checkMeaning(request.meaning());
        if (glossaryService.countOf(ownerId) >= GlossaryService.MAX_ENTRIES_PER_LIST) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximal " + GlossaryService.MAX_ENTRIES_PER_LIST + " Glossar-Eintraege pro Liste");
        }
        if (glossaryService.findByTerm(ownerId, term).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dieser Begriff steht bereits im Glossar");
        }
        GlossaryEntry entry = GlossaryEntry.create(ownerId, term, meaning);
        saveHandlingDuplicate(entry);
        return Dtos.GlossaryEntryView.of(entry);
    }

    private Dtos.GlossaryEntryView updateIn(UUID ownerId, UUID id,
                                            Dtos.GlossaryEntryRequest request) {
        GlossaryEntry entry = requireEntry(ownerId, id);
        String term = requireTerm(request.term());
        String meaning = checkMeaning(request.meaning());
        String newKey = GlossaryEntry.normalizeKey(term);
        if (!newKey.equals(entry.getTermKey())
                && glossaryService.findByTerm(ownerId, term).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dieser Begriff steht bereits im Glossar");
        }
        entry.setTerm(term);
        entry.setMeaning(meaning);
        entry.setUpdatedAt(Instant.now());
        saveHandlingDuplicate(entry);
        return Dtos.GlossaryEntryView.of(entry);
    }

    private GlossaryService.ImportResult importInto(UUID ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keine Datei uebermittelt");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Datei ist zu gross (max. " + (MAX_IMPORT_BYTES / (1024 * 1024)) + " MB)");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datei konnte nicht gelesen werden");
        }
        if (GlossaryCsv.looksBinary(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das sieht nicht nach einer Textdatei aus - bitte eine CSV-Datei waehlen "
                            + "(in Excel: Speichern unter -> CSV)");
        }
        return glossaryService.importCsv(ownerId, content);
    }

    private ResponseEntity<byte[]> csv(UUID ownerId, String filenamePart) {
        byte[] csv = GlossaryCsv.export(glossaryService.entriesOf(ownerId));
        String filename = "glossar_" + filenamePart + "_" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    private static List<Dtos.GlossaryEntryView> view(List<GlossaryEntry> entries) {
        return entries.stream().map(Dtos.GlossaryEntryView::of).toList();
    }

    /** Das gemeinsame Glossar aendert nur, wer die ganze Installation verantwortet. */
    private static void requireAdmin() {
        if (!CurrentUser.get().isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Nur Admins duerfen das gemeinsame Glossar aendern");
        }
    }

    /** Benutzername im Dateinamen auf unproblematische Zeichen beschraenken. */
    private static String safeFilenamePart(String username) {
        String cleaned = username == null ? "" : username.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isEmpty() ? "export" : cleaned;
    }

    /**
     * Der Unique-Index je Geltungsbereich faengt das Rennen zwischen Pruefung und
     * Insert ab - der Konflikt wird als 409 gemeldet statt als 500.
     */
    private void saveHandlingDuplicate(GlossaryEntry entry) {
        try {
            repo.save(entry);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dieser Begriff steht bereits im Glossar");
        }
    }

    /**
     * Eintrag der angegebenen Liste. Ein fremder Eintrag ist "nicht gefunden" -
     * ueber einen anderen Pfad ist er auch nicht erreichbar, und die ID eines
     * anderen Nutzers soll nichts verraten.
     */
    private GlossaryEntry requireEntry(UUID ownerId, UUID id) {
        return repo.findById(id)
                .filter(e -> ownerId == null ? e.isShared() : ownerId.equals(e.getOwnerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Glossar-Eintrag nicht gefunden"));
    }

    private String requireTerm(String raw) {
        String term = raw == null ? "" : raw.strip();
        if (term.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Begriff darf nicht leer sein");
        }
        if (term.length() > GlossaryEntry.MAX_TERM_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Begriff ist zu lang (max. " + GlossaryEntry.MAX_TERM_LENGTH + " Zeichen)");
        }
        return term;
    }

    private String checkMeaning(String raw) {
        if (raw == null) return null;
        String meaning = raw.strip();
        if (meaning.isEmpty()) return null;
        if (meaning.length() > GlossaryEntry.MAX_MEANING_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bedeutung ist zu lang (max. " + GlossaryEntry.MAX_MEANING_LENGTH + " Zeichen)");
        }
        return meaning;
    }
}
