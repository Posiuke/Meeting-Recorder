package bbbbot.sharing;

import bbbbot.domain.AppUser;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.ShareGrantRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Rechtepruefung fuer Aufnahmen: Lesen darf der Besitzer sowie jeder, mit dem
 * die Aufnahme direkt oder ueber eine Gruppe geteilt wurde. Loeschen und
 * Teilen darf nur der Besitzer (oder ein Admin).
 */
@Service
public class AccessService {

    private final RecordingRepo recordingRepo;
    private final ShareGrantRepo shareRepo;

    public AccessService(RecordingRepo recordingRepo, ShareGrantRepo shareRepo) {
        this.recordingRepo = recordingRepo;
        this.shareRepo = shareRepo;
    }

    public Recording requireReadable(UUID recordingId, AppUser user) {
        Recording recording = recordingRepo.findById(recordingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aufnahme nicht gefunden"));
        if (canRead(recording, user)) return recording;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Aufnahme");
    }

    public Recording requireOwner(UUID recordingId, AppUser user) {
        Recording recording = recordingRepo.findById(recordingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Aufnahme nicht gefunden"));
        if (recording.getOwnerId().equals(user.getId()) || user.isAdmin()) return recording;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur der Besitzer darf diese Aktion ausfuehren");
    }

    public boolean canRead(Recording recording, AppUser user) {
        if (user.isAdmin()) return true;
        if (recording.getOwnerId().equals(user.getId())) return true;
        return shareRepo.hasAccess(recording.getId(), user.getId());
    }
}
