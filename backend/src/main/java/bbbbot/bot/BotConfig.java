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
        boolean keepaliveEnabled,
        long keepaliveIntervalMs,
        String keepaliveMessage,
        String keepalivePrefix,
        boolean autoReconnect,
        int reconnectMaxAttempts,
        long reconnectBackoffBaseMs,
        double reconnectBackoffFactor,
        int segmentMinutes,
        long minAudioBytes
) {
    public static BotConfig fromSettings(SettingsService settings) {
        return new BotConfig(
                settings.get(SettingsService.BOT_CHAT_START_COMMAND),
                settings.get(SettingsService.BOT_CHAT_STOP_COMMAND),
                settings.getBool(SettingsService.BOT_SEND_CHAT_WARNING),
                settings.get(SettingsService.BOT_WARN_MESSAGE),
                settings.getInt(SettingsService.BOT_RECORD_MIN_OTHERS),
                settings.getLong(SettingsService.BOT_CHECK_INTERVAL_MS),
                settings.getBool(SettingsService.BOT_KEEPALIVE_ENABLED),
                settings.getLong(SettingsService.BOT_KEEPALIVE_INTERVAL_MS),
                settings.get(SettingsService.BOT_KEEPALIVE_MESSAGE),
                settings.get(SettingsService.BOT_KEEPALIVE_PREFIX),
                settings.getBool(SettingsService.BOT_AUTO_RECONNECT),
                settings.getInt(SettingsService.BOT_RECONNECT_MAX_ATTEMPTS),
                settings.getLong(SettingsService.BOT_RECONNECT_BACKOFF_BASE_MS),
                settings.getDouble(SettingsService.BOT_RECONNECT_BACKOFF_FACTOR),
                settings.getInt(SettingsService.RECORDING_SEGMENT_MINUTES),
                settings.getLong(SettingsService.RECORDING_MIN_AUDIO_BYTES)
        );
    }

    /** Warnmeldung mit eingesetztem Stop-Befehl und angehaengtem Session-Marker. */
    public String buildWarnMessage(String marker) {
        String msg = warnMessage
                .replace("${STOP}", chatStopCommand)
                .replace("${CHAT_STOP_MESSAGE}", chatStopCommand)
                .replace("${START}", chatStartCommand);
        return msg + " [" + marker + "]";
    }
}
