package bbbbot.auth;

import bbbbot.domain.AppUser;
import bbbbot.repository.Repositories.AppUserRepo;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Haelt fest, wer zuletzt im Frontend aktiv war. Die Anmeldung ist zustandslos
 * (JWT), es gibt also keine Sitzung, die man zaehlen koennte - "angemeldet"
 * heisst hier folglich "hat vor kurzem eine Anfrage gestellt".
 *
 * Geschrieben wird bewusst gedrosselt: Ohne die Drossel wuerde jede Anfrage ein
 * UPDATE auf app_user ausloesen (die Aufnahme-Uebersicht pollt im Sekundentakt).
 */
@Service
public class UserActivityService {

    /** Kuerzester Abstand zwischen zwei Schreibvorgaengen pro Nutzer. */
    private static final Duration WRITE_INTERVAL = Duration.ofSeconds(60);

    /**
     * Bis zu dieser Stille gilt ein Nutzer als angemeldet. Etwas mehr als das
     * Schreibintervall, damit ein aktiver Nutzer nicht zwischen den Zustaenden
     * flackert.
     */
    public static final Duration ONLINE_WINDOW = Duration.ofMinutes(5);

    private final AppUserRepo userRepo;
    /** Letzter geschriebener Zeitpunkt je Nutzer - nur zum Drosseln. */
    private final Map<UUID, Instant> lastWrite = new ConcurrentHashMap<>();

    public UserActivityService(AppUserRepo userRepo) {
        this.userRepo = userRepo;
    }

    /** Aktivitaet des Nutzers vermerken; Fehler bleiben ohne Folgen fuer die Anfrage. */
    public void touch(AppUser user) {
        Instant now = Instant.now();
        Instant written = lastWrite.get(user.getId());
        if (written != null && written.isAfter(now.minus(WRITE_INTERVAL))) {
            return;
        }
        lastWrite.put(user.getId(), now);
        try {
            user.setLastSeenAt(now);
            userRepo.save(user);
        } catch (RuntimeException e) {
            // Die Anzeige "wer ist online" ist kein Grund, eine Anfrage scheitern
            // zu lassen - beim naechsten Intervall wird es erneut versucht.
            lastWrite.remove(user.getId());
        }
    }

    /** Gilt der Nutzer nach {@link #ONLINE_WINDOW} noch als angemeldet? */
    public static boolean isOnline(Instant lastSeenAt) {
        return lastSeenAt != null && lastSeenAt.isAfter(Instant.now().minus(ONLINE_WINDOW));
    }
}
