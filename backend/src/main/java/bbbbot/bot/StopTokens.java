package bbbbot.bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tokens fuer den anonymen Stopp-Link: 256 Bit Zufall, URL-tauglich. Der Bot
 * haelt nur den SHA-256-Hash im Speicher - der Klartext steht allein im Chat.
 */
public final class StopTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Obergrenze fuer eingehende Tokens; echte sind 43 Zeichen lang. */
    public static final int MAX_LENGTH = 100;

    private StopTokens() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hash eines Tokens; null bei offensichtlich unpassender Eingabe. */
    public static String hash(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_LENGTH
                || !token.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Vergleich in konstanter Zeit. */
    static boolean matches(String expectedHash, String actualHash) {
        if (expectedHash == null || actualHash == null) return false;
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII));
    }
}
