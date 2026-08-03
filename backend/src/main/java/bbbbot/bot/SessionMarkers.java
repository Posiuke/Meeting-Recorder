package bbbbot.bot;

import java.security.SecureRandom;

/**
 * Zwei-Marker-System pro Bot-Instanz (Portierung von src/chat/marker.ts):
 * - activeMarker: haengt an der Warnmeldung beim Aufnahmestart; STOP/START
 *   werden nur im Chat NACH diesem Marker gesucht.
 * - cutoffMarker: haengt an der Stop-Bestaetigung des Bots; verhindert, dass
 *   der Bot auf das Wort "STARTRECORDING" in seiner eigenen Hinweismeldung
 *   reagiert (Selbst-Trigger-Schutz).
 */
public class SessionMarkers {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private volatile String active;
    private volatile String cutoff;

    public static String generate() {
        StringBuilder sb = new StringBuilder("REC");
        for (int i = 0; i < 12; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public String getActive() { return active; }
    public void setActive(String marker) { this.active = marker; }
    public void clearActive() { this.active = null; }
    public boolean hasActive() { return active != null; }

    public String getCutoff() { return cutoff; }
    public void setCutoff(String marker) { this.cutoff = marker; }
    public void clearCutoff() { this.cutoff = null; }
    public boolean hasCutoff() { return cutoff != null; }
}
