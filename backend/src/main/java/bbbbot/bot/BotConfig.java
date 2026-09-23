package bbbbot.bot;

import bbbbot.settings.SettingsService;

/**
 * Unveraenderlicher Einstellungs-Schnappschuss fuer eine Bot-Instanz.
 * Wird beim Bot-Start aus den aktuellen Admin-Einstellungen erzeugt.
 */
public record BotConfig(
        String chatStartCommand,
        String chatStopCommand,
        boolean sendChatWarning,
        String warnMessage,
        int recordMinOthers,
        long checkIntervalMs,
        boolean autoReconnect,
        int reconnectMaxAttempts,
        long reconnectBackoffBaseMs,
        double reconnectBackoffFactor,
        int segmentMinutes,
        long minAudioBytes,
        boolean anonymousStopEnabled,
        String publicUrl
) {
    public static BotConfig fromSettings(SettingsService settings) {
        return new BotConfig(
                settings.get(SettingsService.BOT_CHAT_START_COMMAND),
                settings.get(SettingsService.BOT_CHAT_STOP_COMMAND),
                settings.getBool(SettingsService.BOT_SEND_CHAT_WARNING),
                settings.get(SettingsService.BOT_WARN_MESSAGE),
                settings.getInt(SettingsService.BOT_RECORD_MIN_OTHERS),
                settings.getLong(SettingsService.BOT_CHECK_INTERVAL_MS),
                settings.getBool(SettingsService.BOT_AUTO_RECONNECT),
                settings.getInt(SettingsService.BOT_RECONNECT_MAX_ATTEMPTS),
                settings.getLong(SettingsService.BOT_RECONNECT_BACKOFF_BASE_MS),
                settings.getDouble(SettingsService.BOT_RECONNECT_BACKOFF_FACTOR),
                settings.getInt(SettingsService.RECORDING_SEGMENT_MINUTES),
                settings.getLong(SettingsService.RECORDING_MIN_AUDIO_BYTES),
                settings.getBool(SettingsService.BOT_ANONYMOUS_STOP_ENABLED),
                settings.get(SettingsService.BOT_PUBLIC_URL)
        );
    }

    /**
     * Ist keine feste Adresse eingetragen ({@code bot.publicUrl}), gilt die, unter
     * der der Nutzer die Anwendung beim Start aufgerufen hat - so wie die
     * Oberflaeche Freigabe-Links aus {@code window.location.origin} baut.
     */
    public BotConfig withFallbackPublicUrl(String appOrigin) {
        if ((publicUrl != null && !publicUrl.isBlank()) || appOrigin == null || appOrigin.isBlank()) {
            return this;
        }
        return new BotConfig(chatStartCommand, chatStopCommand, sendChatWarning, warnMessage,
                recordMinOthers, checkIntervalMs, autoReconnect, reconnectMaxAttempts,
                reconnectBackoffBaseMs, reconnectBackoffFactor, segmentMinutes, minAudioBytes,
                anonymousStopEnabled, appOrigin);
    }

    /** Platzhalter fuer den anonymen Stopp-Link in der Warnmeldung. */
    static final String STOP_URL_PLACEHOLDER = "${STOP_URL}";

    /**
     * Basisadresse fuer den anonymen Stopp-Link ohne abschliessenden Schraegstrich,
     * oder null, wenn die Funktion aus ist bzw. keine Adresse eingetragen ist.
     */
    public String stopUrlBase() {
        if (!anonymousStopEnabled || publicUrl == null || publicUrl.isBlank()) return null;
        return publicUrl.trim().replaceAll("/+$", "");
    }

    /** Warnmeldung ohne Stopp-Link. */
    public String buildWarnMessage(String marker) {
        return buildWarnMessage(marker, null);
    }

    /**
     * Warnmeldung mit eingesetztem Stop-Befehl, optionalem Stopp-Link und
     * angehaengtem Session-Marker.
     *
     * <p>Der Link landet an der Stelle von {@code ${STOP_URL}}. Fehlt der
     * Platzhalter (z.B. in einer frueher angepassten Meldung), wird er als
     * eigener Satz angehaengt - sonst ginge der Link stillschweigend verloren.
     * Ohne Link verschwindet der Platzhalter.
     */
    public String buildWarnMessage(String marker, String stopUrl) {
        String msg = warnMessage
                .replace("${STOP}", chatStopCommand)
                .replace("${CHAT_STOP_MESSAGE}", chatStopCommand)
                .replace("${START}", chatStartCommand);
        if (stopUrl == null) {
            if (msg.contains(STOP_URL_PLACEHOLDER)) {
                msg = msg.replace(STOP_URL_PLACEHOLDER, "").replaceAll(" {2,}", " ").trim();
            }
        } else if (msg.contains(STOP_URL_PLACEHOLDER)) {
            msg = msg.replace(STOP_URL_PLACEHOLDER, stopUrl);
        } else {
            msg = msg + " Ohne Chat-Nachricht beenden und verwerfen: " + stopUrl;
        }
        return msg + " [" + marker + "]";
    }
}
