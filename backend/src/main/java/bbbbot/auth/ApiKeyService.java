package bbbbot.auth;

import bbbbot.domain.ApiKey;
import bbbbot.repository.Repositories.ApiKeyRepo;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Verwaltet die persoenlichen API-Schluessel: anlegen, pruefen, loeschen.
 *
 * <p>Der Schluessel wird nur beim Anlegen zurueckgegeben; in der Datenbank liegt
 * ausschliesslich sein SHA-256-Abdruck. Geht er verloren, wird ein neuer angelegt -
 * wiederherstellen kann ihn niemand, auch kein Admin.
 */
@Service
public class ApiKeyService {

    /** Missbrauchsgrenze; wer mehr braucht, hat ein Aufraeumproblem. */
    public static final int MAX_KEYS_PER_USER = 25;

    /** 32 Byte Zufall = 256 Bit; als Base64url 43 Zeichen. */
    private static final int TOKEN_BYTES = 32;

    /**
     * Die letzte Nutzung wird nur in diesem Abstand fortgeschrieben - sonst
     * kostet jeder API-Aufruf einen Schreibvorgang.
     */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final ApiKeyRepo repo;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepo repo) {
        this.repo = repo;
    }

    /**
     * Neu angelegter Schluessel samt Klartext-Token. Das Token steht nur in
     * dieser Antwort - danach ist es nicht mehr abrufbar.
     */
    public record NewKey(ApiKey key, String token) {
    }

    public List<ApiKey> keysOf(UUID ownerId) {
        return repo.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public NewKey create(UUID ownerId, String name, boolean readOnly, Instant expiresAt) {
        String token = ApiKey.TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomBytes());
        ApiKey key = ApiKey.create(ownerId, name, hash(token),
                token.substring(0, ApiKey.VISIBLE_PREFIX_LENGTH), readOnly, expiresAt);
        repo.save(key);
        return new NewKey(key, token);
    }

    public Optional<ApiKey> findOwn(UUID ownerId, UUID keyId) {
        return repo.findById(keyId).filter(k -> k.getOwnerId().equals(ownerId));
    }

    public void delete(ApiKey key) {
        repo.delete(key);
    }

    public long countOf(UUID ownerId) {
        return repo.countByOwnerId(ownerId);
    }

    /**
     * Prueft ein vorgelegtes Token. Abgelaufene Schluessel gelten als ungueltig
     * (aber nicht als geloescht - der Nutzer soll sehen, dass sie abgelaufen sind).
     */
    public Optional<ApiKey> authenticate(String token) {
        if (token == null || token.isBlank() || !token.startsWith(ApiKey.TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<ApiKey> found = repo.findByTokenHash(hash(token));
        if (found.isEmpty()) return Optional.empty();
        ApiKey key = found.get();
        if (key.isExpired(Instant.now())) return Optional.empty();
        touch(key);
        return found;
    }

    /** Letzte Nutzung fortschreiben, aber hoechstens minuetlich. */
    private void touch(ApiKey key) {
        Instant now = Instant.now();
        Instant last = key.getLastUsedAt();
        if (last != null && last.isAfter(now.minus(LAST_USED_RESOLUTION))) return;
        key.setLastUsedAt(now);
        repo.save(key);
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }

    /** SHA-256-Abdruck als Hex - siehe {@link ApiKey} zur Wahl des Verfahrens. */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in dieser JVM", e);
        }
    }
}
