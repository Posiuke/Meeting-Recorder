package bbbbot.auth.oidc;

/**
 * Gescheiterte OIDC-Anmeldung. {@code code} geht als Kennung an die
 * Oberflaeche (die daraus eine uebersetzte Meldung macht), {@code getMessage()}
 * mit Einzelheiten nur ins Protokoll.
 */
public class OidcException extends Exception {

    private final String code;

    public OidcException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
