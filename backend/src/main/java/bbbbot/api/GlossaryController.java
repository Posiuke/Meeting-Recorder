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
 * Persoenliches Glossar: Jeder Nutzer pflegt seine eigenen Abkuerzungen und
 * Fachbegriffe. Sie werden bei der KI-Glaettung des Transkripts mitgegeben -
 * verwendet wird das Glossar des Aufnahme-Besitzers.
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

    private final GlossaryEntryRepo repo;
    private final GlossaryService glossaryService;

    public GlossaryController(GlossaryEntryRepo repo, GlossaryService glossaryService) {
        this.repo = repo;
        this.glossaryService = glossaryService;
    }

    @GetMapping
    public List<Dtos.GlossaryEntryView> list() {
        AppUser user = CurrentUser.get();
        return glossaryService.entriesOf(user.getId()).stream()
                .map(Dtos.GlossaryEntryView::of)
                .toList();
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
        byte[] csv = GlossaryCsv.export(glossaryService.entriesOf(user.getId()));
        String filename = "glossar_" + safeFilenamePart(user.getUsername()) + "_"
                + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    /**
     * Liest eine CSV-Datei ins eigene Glossar ein (zusammenfuehren, nie loeschen).
     * Das Ergebnis nennt Zahlen und Hinweise, damit sichtbar ist, was die Datei
     * bewirkt hat.
     */
    @PostMapping("/import")
    public GlossaryService.ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        AppUser user = CurrentUser.get();
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
        return glossaryService.importCsv(user.getId(), content);
    }

    /** Benutzername im Dateinamen auf unproblematische Zeichen beschraenken. */
    private static String safeFilenamePart(String username) {
        String cleaned = username == null ? "" : username.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.isEmpty() ? "export" : cleaned;
    }

    @PostMapping
    public Dtos.GlossaryEntryView create(@RequestBody Dtos.GlossaryEntryRequest request) {
        AppUser user = CurrentUser.get();
        String term = requireTerm(request.term());
        String meaning = checkMeaning(request.meaning());
        if (repo.countByOwnerId(user.getId()) >= GlossaryService.MAX_ENTRIES_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximal " + GlossaryService.MAX_ENTRIES_PER_USER + " Glossar-Eintraege pro Nutzer");
        }
        if (repo.findByOwnerIdAndTermKey(user.getId(), GlossaryEntry.normalizeKey(term)).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dieser Begriff steht bereits im Glossar");
        }
        GlossaryEntry entry = GlossaryEntry.create(user.getId(), term, meaning);
        saveHandlingDuplicate(entry);
        return Dtos.GlossaryEntryView.of(entry);
    }

    @PutMapping("/{id}")
    public Dtos.GlossaryEntryView update(@PathVariable UUID id,
                                         @RequestBody Dtos.GlossaryEntryRequest request) {
        AppUser user = CurrentUser.get();
        GlossaryEntry entry = requireOwn(id, user);
        String term = requireTerm(request.term());
        String meaning = checkMeaning(request.meaning());
        String newKey = GlossaryEntry.normalizeKey(term);
        if (!newKey.equals(entry.getTermKey())
                && repo.findByOwnerIdAndTermKey(user.getId(), newKey).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dieser Begriff steht bereits im Glossar");
        }
        entry.setTerm(term);
        entry.setMeaning(meaning);
        entry.setUpdatedAt(Instant.now());
        saveHandlingDuplicate(entry);
        return Dtos.GlossaryEntryView.of(entry);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        repo.delete(requireOwn(id, user));
    }

    /**
     * Der Unique-Index (owner_id, term_key) faengt das Rennen zwischen Pruefung
     * und Insert ab - der Konflikt wird als 409 gemeldet statt als 500.
     */
    private void saveHandlingDuplicate(GlossaryEntry entry) {
        try {
            repo.save(entry);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dieser Begriff steht bereits im Glossar");
        }
    }

    private GlossaryEntry requireOwn(UUID id, AppUser user) {
        return repo.findById(id)
                .filter(e -> e.getOwnerId().equals(user.getId()))
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
