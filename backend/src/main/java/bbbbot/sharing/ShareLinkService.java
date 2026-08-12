package bbbbot.sharing;

import bbbbot.domain.ShareLink;
import bbbbot.repository.Repositories.ShareLinkRepo;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Verwaltet die oeffentlichen Freigabe-Links einer Aufnahme: anlegen, aufloesen,
 * widerrufen. Ein Link gibt genau eine Aufnahme zum Lesen frei - Video, Audio,
 * Transkript und Zusammenfassung - und braucht keine Anmeldung.
 */
@Service
public class ShareLinkService {

    /** Missbrauchsgrenze je Aufnahme; wer mehr braucht, sollte alte widerrufen. */
    public static final int MAX_LINKS_PER_RECORDING = 20;

    /** Obergrenze fuer die gewaehlte Laufzeit (10 Jahre) - alles darueber ist "unbegrenzt". */
    public static final int MAX_EXPIRY_DAYS = 3650;

    /** 32 Byte Zufall = 256 Bit; als base64url 43 Zeichen. */
    private static final int TOKEN_BYTES = 32;

    /**
     * Die Zugriffszaehlung wird nur in diesem Abstand fortgeschrieben - sonst
     * kostet jedes Neuladen der Freigabe-Ansicht einen Schreibvorgang.
     */
    private static final Duration VIEW_RESOLUTION = Duration.ofMinutes(1);

    private final ShareLinkRepo repo;
    private final SecureRandom random = new SecureRandom();

    public ShareLinkService(ShareLinkRepo repo) {
        this.repo = repo;
    }

    public List<ShareLink> linksOf(UUID recordingId) {
        return repo.findByRecordingIdOrderByCreatedAtDesc(recordingId);
    }

    public long countOf(UUID recordingId) {
        return repo.countByRecordingId(recordingId);
    }

    /**
     * Legt einen neuen Link an.
     *
     * @param expiresInDays Laufzeit in Tagen oder {@code null} fuer "bis zum Widerruf"
     */
    public ShareLink create(UUID recordingId, UUID createdBy, Integer expiresInDays) {
        Instant expiresAt = expiresInDays == null ? null
                : Instant.now().plus(expiresInDays, ChronoUnit.DAYS);
        ShareLink link = ShareLink.create(recordingId, newToken(), createdBy, expiresAt);
        repo.save(link);
        return link;
    }

    public Optional<ShareLink> findOfRecording(UUID recordingId, UUID linkId) {
        return repo.findById(linkId).filter(l -> l.getRecordingId().equals(recordingId));
    }

    public void delete(ShareLink link) {
        repo.delete(link);
    }

    /**
     * Loest ein vorgelegtes Token auf. Abgelaufene Links gelten als unbekannt -
     * der Aufrufer erfaehrt bewusst nicht, ob es den Link je gab.
     */
    public Optional<ShareLink> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return repo.findByToken(token).filter(l -> !l.isExpired(Instant.now()));
    }

    /** Zugriff auf die Freigabe-Ansicht vermerken (hoechstens minuetlich). */
    public void recordView(ShareLink link) {
        Instant now = Instant.now();
        Instant last = link.getLastViewedAt();
        if (last != null && last.isAfter(now.minus(VIEW_RESOLUTION))) return;
        link.setLastViewedAt(now);
        link.setViews(link.getViews() + 1);
        repo.save(link);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
