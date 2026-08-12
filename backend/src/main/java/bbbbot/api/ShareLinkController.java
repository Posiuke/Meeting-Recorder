package bbbbot.api;

import bbbbot.auth.CurrentUser;
import bbbbot.domain.AppUser;
import bbbbot.domain.Recording;
import bbbbot.domain.ShareGrant;
import bbbbot.domain.ShareLink;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.ShareGrantRepo;
import bbbbot.sharing.ShareLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Einloesen eines Freigabe-Links durch einen angemeldeten Nutzer. Anders als
 * {@link PublicShareController} setzt dieser Bereich eine Anmeldung voraus - er
 * liegt daher bewusst nicht unter {@code /api/public}.
 *
 * <p>Ein kontogebundener Link (siehe {@link ShareLink}) fuehrt den Empfaenger
 * ueber die Anmeldung: Danach wird die Aufnahme mit seinem Konto geteilt, und er
 * sieht sie in der normalen Aufnahmen-Ansicht - mit allem, was eine Freigabe
 * bietet, und nachvollziehbar in der Freigabe-Liste des Besitzers.
 */
@RestController
@RequestMapping("/api/share-links")
public class ShareLinkController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShareLinkController.class);

    private final ShareLinkService shareLinks;
    private final RecordingRepo recordingRepo;
    private final ShareGrantRepo shareRepo;

    public ShareLinkController(ShareLinkService shareLinks, RecordingRepo recordingRepo,
                               ShareGrantRepo shareRepo) {
        this.shareLinks = shareLinks;
        this.recordingRepo = recordingRepo;
        this.shareRepo = shareRepo;
    }

    /** Rahmenbedingungen fuers Frontend: Darf der Admin-Einstellung nach ohne Anmeldung geteilt werden? */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("publicLinksAllowed", shareLinks.publicLinksAllowed());
    }

    /**
     * Freigabe-Link einloesen: Die Aufnahme wird mit dem angemeldeten Nutzer
     * geteilt (falls er sie nicht ohnehin schon sehen darf) und deren Kennung
     * zurueckgegeben, damit das Frontend zur Aufnahme springen kann.
     *
     * <p>Mehrfaches Einloesen ist unschaedlich: Es entsteht hoechstens eine
     * Freigabe. Bei einem offenen Link (ohne Anmeldung nutzbar) wird bewusst
     * KEINE Freigabe angelegt - dort ist der Zugriff schon ohne Konto moeglich,
     * und eine stille Dauer-Freigabe waere eine Ueberraschung fuer den Besitzer.
     */
    @PostMapping("/{token}/claim")
    public Dtos.ShareLinkClaimView claim(@PathVariable String token) {
        AppUser user = CurrentUser.get();
        ShareLink link = shareLinks.resolve(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dieser Freigabe-Link ist ungueltig oder abgelaufen"));
        Recording recording = recordingRepo.findById(link.getRecordingId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Die freigegebene Aufnahme existiert nicht mehr"));

        boolean owner = recording.getOwnerId().equals(user.getId());
        boolean shared = false;
        if (shareLinks.requiresLogin(link) && !owner
                && !shareRepo.hasAccess(recording.getId(), user.getId())) {
            // Als Freigabe des Besitzers eintragen (createdBy = Ersteller des Links),
            // damit sie in dessen Freigabe-Liste auftaucht und dort widerrufbar ist.
            shareRepo.save(ShareGrant.forUser(recording.getId(), user.getId(), link.getCreatedBy()));
            shared = true;
            log.info("Freigabe-Link {} von {} eingeloest - Aufnahme {} mit dem Konto geteilt",
                    link.getId(), user.getUsername(), recording.getId());
        }
        shareLinks.recordView(link);
        return new Dtos.ShareLinkClaimView(recording.getId(), recording.getTitle(), shared);
    }
}
