package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.docs.DocumentTextExtractor;
import bbbbot.docs.RecordingDocumentService;
import bbbbot.domain.AppUser;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingDocument;
import bbbbot.sharing.AccessService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Beigefuegte Unterlagen einer Aufnahme: Tagesordnung, Folien, ein Papier, das
 * durchgesprochen wurde. Ihr Text geht in die KI-Auswertung ein, damit die
 * Zusammenfassung das Thema kennt und nicht nur das Gesprochene.
 *
 * <p>Hinzufuegen, Ersetzen und Loeschen darf nur der <b>Besitzer</b> der Aufnahme;
 * lesen und herunterladen darf jeder, der die Aufnahme sehen darf - eine Unterlage
 * gehoert zur Aufnahme wie ihr Transkript. In der oeffentlichen Freigabe-Ansicht
 * erscheinen Unterlagen bewusst NICHT: Was intern zur Vorbereitung gehoert, ist
 * nicht automatisch etwas fuer Empfaenger eines Links.
 */
@RestController
@RequestMapping("/api/recordings/{id}/documents")
public class RecordingDocumentController {

    private final AccessService access;
    private final RecordingDocumentService documents;

    public RecordingDocumentController(AccessService access, RecordingDocumentService documents) {
        this.access = access;
        this.documents = documents;
    }

    @GetMapping
    public List<Dtos.RecordingDocumentView> list(@PathVariable UUID id) {
        AppUser user = CurrentUser.get();
        access.requireReadable(id, user);
        return documents.documentsOf(id).stream().map(Dtos.RecordingDocumentView::of).toList();
    }

    /**
     * Unterlage hinzufuegen. Die Antwort ist die ganze Liste: Die Textextraktion
     * laeuft im Hintergrund, und die Oberflaeche zeigt danach den Zustand aller
     * Unterlagen an.
     */
    @PostMapping
    public List<Dtos.RecordingDocumentView> add(@PathVariable UUID id,
                                                @RequestParam("file") MultipartFile file) {
        AppUser user = CurrentUser.get();
        Recording recording = access.requireOwner(id, user);
        if (!documents.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Beigefuegte Unterlagen sind auf diesem Server abgeschaltet");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keine Datei uebermittelt");
        }
        long maxBytes = documents.maxBytes();
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Datei ist zu gross (max. " + (maxBytes / (1024 * 1024)) + " MB)");
        }
        if (documents.countOf(id) >= RecordingDocumentService.MAX_DOCUMENTS_PER_RECORDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximal " + RecordingDocumentService.MAX_DOCUMENTS_PER_RECORDING
                            + " Unterlagen je Aufnahme");
        }
        requireSupportedType(file.getOriginalFilename());

        try (InputStream in = file.getInputStream()) {
            documents.add(recording, file.getOriginalFilename(), file.getContentType(),
                    in, user.getId());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unterlage konnte nicht gespeichert werden: " + e.getMessage());
        }
        return list(id);
    }

    /**
     * Textextraktion erneut anstossen (nur Besitzer) - gedacht fuer den Fall, dass
     * der Tika-Server erst nach dem Hochladen eingerichtet wurde.
     */
    @PostMapping("/{documentId}/extract")
    public List<Dtos.RecordingDocumentView> extract(@PathVariable UUID id,
                                                    @PathVariable UUID documentId) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        documents.retry(requireDocument(id, documentId));
        return list(id);
    }

    @DeleteMapping("/{documentId}")
    public List<Dtos.RecordingDocumentView> delete(@PathVariable UUID id,
                                                   @PathVariable UUID documentId) {
        AppUser user = CurrentUser.get();
        access.requireOwner(id, user);
        documents.delete(requireDocument(id, documentId));
        return list(id);
    }

    /**
     * Die Originaldatei. Wird als Anhang ausgeliefert (nicht im Browser
     * dargestellt): Der Inhalt kommt von einem Nutzer, und eine HTML-Datei soll
     * nicht im Kontext der Anwendung ausgefuehrt werden.
     */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable UUID id,
                                                       @PathVariable UUID documentId) {
        AppUser user = CurrentUser.get();
        access.requireReadable(id, user);
        RecordingDocument document = requireDocument(id, documentId);
        Path file = Path.of(document.getStoredPath());
        if (!Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Datei nicht vorhanden");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + document.getFilename() + "\"")
                .body(new FileSystemResource(file));
    }

    /** Der extrahierte Text - so ist nachsehbar, was die KI aus der Datei bekommt. */
    @GetMapping("/{documentId}/text")
    public ResponseEntity<String> text(@PathVariable UUID id, @PathVariable UUID documentId) {
        AppUser user = CurrentUser.get();
        access.requireReadable(id, user);
        RecordingDocument document = requireDocument(id, documentId);
        if (!document.isUsable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kein Text vorhanden");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain; charset=utf-8"))
                .body(document.getExtractedText());
    }

    private RecordingDocument requireDocument(UUID recordingId, UUID documentId) {
        try {
            return documents.require(recordingId, documentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Positivliste der Endungen. Eine Meldung mit den erlaubten Typen ist hier mehr
     * wert als ein Tika-Fehler zu einer .zip - und ohne eingerichteten Tika-Server
     * sagt sie gleich, dass eine PDF nichts wird.
     */
    private void requireSupportedType(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename;
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!DocumentTextExtractor.isAllowed(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Dateityp nicht unterstuetzt: ." + extension + " (erlaubt: "
                            + String.join(", ", DocumentTextExtractor.allowedExtensions()) + ")");
        }
    }
}
