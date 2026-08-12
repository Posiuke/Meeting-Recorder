package bbbbot.settings;

import bbbbot.domain.AppSetting;
import bbbbot.repository.Repositories.AppSettingRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zentrale, zur Laufzeit aenderbare Einstellungen (STT-/LLM-Parameter, Zeitfenster,
 * Bot-Verhalten). Persistiert in der Tabelle app_setting; fehlende Schluessel
 * fallen auf die Defaults zurueck. Nur Schluessel aus DEFAULTS sind zulaessig.
 */
@Service
public class SettingsService {

    /** STT-Anbieter: "local" = Whisper-ASR-Webservice im Intranet, "openai" = OpenAI-kompatible Cloud-API. */
    public static final String WHISPER_PROVIDER = "whisper.provider";
    public static final String WHISPER_URL = "whisper.url";
    public static final String WHISPER_OPENAI_URL = "whisper.openaiUrl";
    public static final String WHISPER_OPENAI_API_KEY = "whisper.openaiApiKey";
    public static final String WHISPER_OPENAI_MODEL = "whisper.openaiModel";
    public static final String WHISPER_LANGUAGE = "whisper.language";
    public static final String WHISPER_OUTPUT = "whisper.output";
    public static final String WHISPER_VAD_FILTER = "whisper.vadFilter";
    public static final String WHISPER_DIARIZE = "whisper.diarize";
    public static final String WHISPER_INITIAL_PROMPT = "whisper.initialPrompt";
    public static final String WHISPER_TIMEOUT_SEC = "whisper.timeoutSec";
    public static final String WHISPER_RETRY_ATTEMPTS = "whisper.retryAttempts";
    public static final String WHISPER_RETRY_BASE_MS = "whisper.retryBaseMs";

    public static final String LLM_BASE_URL = "llm.baseUrl";
    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_API_KEY = "llm.apiKey";
    public static final String LLM_TEMPERATURE = "llm.temperature";
    public static final String LLM_MAX_TOKENS = "llm.maxTokens";
    /**
     * Internes "Nachdenken" von Reasoning-Modellen (Qwen3 & Co.) abschalten.
     * Das Nachdenken laeuft im SELBEN Token-Budget wie die Antwort: Ist es an,
     * verbraucht das Modell das Budget und liefert {@code content: null} - die
     * Transkript-Glaettung bekommt dann nie eine Antwort. Fuer Glaetten und
     * Zusammenfassen bringt Nachdenken nichts, deshalb ist es standardmaessig aus.
     */
    public static final String LLM_DISABLE_THINKING = "llm.disableThinking";
    public static final String LLM_TIMEOUT_SEC = "llm.timeoutSec";
    public static final String LLM_RETRY_ATTEMPTS = "llm.retryAttempts";
    public static final String LLM_RETRY_BASE_MS = "llm.retryBaseMs";

    public static final String SUMMARY_LANGUAGE = "summary.language";
    public static final String SUMMARY_CHUNK_CHARS = "summary.chunkChars";
    public static final String SUMMARY_SYSTEM_PROMPT = "summary.systemPrompt";
    public static final String SUMMARY_MIN_AUDIO_MS = "summary.minAudioMs";
    public static final String SUMMARY_MIN_TRANSCRIPT_CHARS = "summary.minTranscriptChars";
    public static final String SUMMARY_MIN_CHAT_CHARS = "summary.minChatChars";

    /** KI-Glaettung des Transkripts vor der Auswertung (Zwischenschritt). */
    public static final String CORRECTION_ENABLED = "correction.enabled";
    public static final String CORRECTION_SYSTEM_PROMPT = "correction.systemPrompt";
    /**
     * Zeichen je Glaettungsschritt (ein LLM-Aufruf). Bestimmt auch das
     * Antwort-Token-Budget. Ganze Saetze werden nie ueber zwei Schritte zerschnitten.
     */
    public static final String CORRECTION_CHUNK_CHARS = "correction.chunkChars";
    /**
     * Notbremse fuer die Satzbildung: Liefert die Spracherkennung keine
     * Satzzeichen, wird nach so vielen Zeichen trotzdem getrennt.
     */
    public static final String CORRECTION_MAX_SENTENCE_CHARS = "correction.maxSentenceChars";
    /** Obergrenze fuer den Glossar-Block im Prompt; 0 = unbegrenzt. */
    public static final String CORRECTION_GLOSSARY_MAX_CHARS = "correction.glossaryMaxChars";

    public static final String PROCESSING_WINDOW_START = "processing.windowStart";
    public static final String PROCESSING_WINDOW_END = "processing.windowEnd";

    public static final String RECORDING_SEGMENT_MINUTES = "recording.segmentMinutes";
    public static final String RECORDING_MP3_BITRATE = "recording.mp3Bitrate";
    public static final String RECORDING_MIN_AUDIO_BYTES = "recording.minAudioBytes";

    public static final String BOT_CHAT_START_COMMAND = "bot.chatStartCommand";
    public static final String BOT_CHAT_STOP_COMMAND = "bot.chatStopCommand";
    public static final String BOT_SEND_CHAT_WARNING = "bot.sendChatWarning";
    public static final String BOT_WARN_MESSAGE = "bot.warnMessage";
    public static final String BOT_RECORD_MIN_OTHERS = "bot.recordMinOthers";
    public static final String BOT_CHECK_INTERVAL_MS = "bot.checkIntervalMs";
    public static final String BOT_KEEPALIVE_ENABLED = "bot.keepaliveEnabled";
    public static final String BOT_KEEPALIVE_INTERVAL_MS = "bot.keepaliveIntervalMs";
    public static final String BOT_KEEPALIVE_MESSAGE = "bot.keepaliveMessage";
    public static final String BOT_KEEPALIVE_PREFIX = "bot.keepalivePrefix";
    public static final String BOT_AUTO_RECONNECT = "bot.autoReconnect";
    public static final String BOT_RECONNECT_MAX_ATTEMPTS = "bot.reconnectMaxAttempts";
    public static final String BOT_RECONNECT_BACKOFF_BASE_MS = "bot.reconnectBackoffBaseMs";
    public static final String BOT_RECONNECT_BACKOFF_FACTOR = "bot.reconnectBackoffFactor";
    /** SSRF-Schutz: komma-getrennte erlaubte Host-Suffixe fuer die Meeting-URL. Leer = keine Einschraenkung. */
    public static final String BOT_ALLOWED_URL_HOSTS = "bot.allowedUrlHosts";

    /** Bildschirmaufnahme im Browser (getDisplayMedia) fuer Nutzer freigeschaltet. */
    public static final String CAPTURE_ENABLED = "capture.enabled";
    /** Obergrenze fuer eine einzelne Bildschirmaufnahme in Megabyte (Plattenschutz). */
    public static final String CAPTURE_MAX_MEGABYTES = "capture.maxMegabytes";
    /** Nach so vielen Minuten ohne neue Daten gilt eine Bildschirmaufnahme als abgebrochen. */
    public static final String CAPTURE_STALE_MINUTES = "capture.staleMinutes";

    /**
     * Duerfen Freigabe-Links ohne Anmeldung genutzt werden? Aus = jeder Link
     * verlangt eine Anmeldung, auch bereits erzeugte (Datenschutz-Notbremse).
     */
    public static final String SHARING_PUBLIC_LINKS = "sharing.publicLinks";

    public static final String CLEANUP_ENABLED = "cleanup.enabled";
    public static final String CLEANUP_OLDER_THAN_DAYS = "cleanup.olderThanDays";

    private static final String DEFAULT_SUMMARY_PROMPT = """
        Du bist ein Assistent, der Meetings praezise zusammenfasst. Erstelle eine strukturierte \
        Zusammenfassung mit folgenden Abschnitten:
        1. Management-Zusammenfassung (max. 5 Saetze)
        2. Teilnehmer & Rollen
        3. Beschluesse und Aufgaben (mit Verantwortlichen und Fristen, falls genannt)
        4. Offene Fragen
        5. Chronologischer Ablauf (stichpunktartig)
        Wichtig: Erfinde nichts. Markiere unklare Stellen ausdruecklich als unklar.""";

    private static final String DEFAULT_CORRECTION_PROMPT = """
        Du glaettest Saetze aus dem Roh-Transkript einer automatischen Spracherkennung. \
        Deine Aufgaben:
        - Fuellwoerter ("aeh", "also", "sozusagen") und Wiederholungen entfernen
        - Satzzeichen, Gross-/Kleinschreibung und Wortformen korrigieren
        - offensichtliche Erkennungsfehler berichtigen, besonders bei Fachbegriffen, \
        Eigennamen und Abkuerzungen
        Strenge Regeln:
        - Inhalt und Aussage NICHT veraendern, nichts hinzuerfinden, nichts zusammenfassen, \
        nichts weglassen
        - jeder Satz bleibt EIN Satz: Saetze nicht zusammenlegen und nicht aufteilen
        - keine Kommentare, keine Ueberschriften, keine Erklaerungen
        - antworte ausschliesslich im Format "Nummer | Satz": eine Zeile je Eingabesatz, \
        dieselben Nummern, dieselbe Reihenfolge
        - ist ein Satz bereits korrekt, gib ihn unveraendert zurueck""";

    private static final String DEFAULT_WARN_MESSAGE =
        "Automatische Audioaufzeichnung wurde gestartet. Wenn Sie die Aufzeichnung verhindern moechten, "
        + "schreiben Sie folgendes in den Chat: ${STOP}";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(WHISPER_PROVIDER, "local");
        DEFAULTS.put(WHISPER_URL, "http://localhost:11436/asr");
        DEFAULTS.put(WHISPER_OPENAI_URL, "https://api.openai.com/v1/audio/transcriptions");
        DEFAULTS.put(WHISPER_OPENAI_API_KEY, "");
        DEFAULTS.put(WHISPER_OPENAI_MODEL, "whisper-1");
        DEFAULTS.put(WHISPER_LANGUAGE, "de");
        DEFAULTS.put(WHISPER_OUTPUT, "json");
        DEFAULTS.put(WHISPER_VAD_FILTER, "true");
        // Sprechererkennung FREISCHALTEN: Nutzer koennen sie dann pro Aufnahme/Upload
        // waehlen. Benoetigt ASR_ENGINE=whisperx, siehe docs/WHISPER_DIARIZATION.md
        DEFAULTS.put(WHISPER_DIARIZE, "false");
        DEFAULTS.put(WHISPER_INITIAL_PROMPT, "");
        DEFAULTS.put(WHISPER_TIMEOUT_SEC, "600");
        DEFAULTS.put(WHISPER_RETRY_ATTEMPTS, "2");
        DEFAULTS.put(WHISPER_RETRY_BASE_MS, "2000");

        DEFAULTS.put(LLM_BASE_URL, "http://localhost:11434/v1");
        DEFAULTS.put(LLM_MODEL, "Qwen3.5-122B");
        DEFAULTS.put(LLM_API_KEY, "");
        DEFAULTS.put(LLM_TEMPERATURE, "0.3");
        DEFAULTS.put(LLM_MAX_TOKENS, "2048");
        DEFAULTS.put(LLM_DISABLE_THINKING, "true");
        DEFAULTS.put(LLM_TIMEOUT_SEC, "300");
        DEFAULTS.put(LLM_RETRY_ATTEMPTS, "2");
        DEFAULTS.put(LLM_RETRY_BASE_MS, "1000");

        DEFAULTS.put(SUMMARY_LANGUAGE, "de");
        DEFAULTS.put(SUMMARY_CHUNK_CHARS, "12000");
        DEFAULTS.put(SUMMARY_SYSTEM_PROMPT, DEFAULT_SUMMARY_PROMPT);
        DEFAULTS.put(SUMMARY_MIN_AUDIO_MS, "60000");
        DEFAULTS.put(SUMMARY_MIN_TRANSCRIPT_CHARS, "50");
        DEFAULTS.put(SUMMARY_MIN_CHAT_CHARS, "20");

        DEFAULTS.put(CORRECTION_ENABLED, "true");
        DEFAULTS.put(CORRECTION_SYSTEM_PROMPT, DEFAULT_CORRECTION_PROMPT);
        // Klein halten: Die Antwort ist etwa so lang wie die Anfrage und muss ins
        // Token-Limit des Modells passen.
        DEFAULTS.put(CORRECTION_CHUNK_CHARS, "3000");
        DEFAULTS.put(CORRECTION_MAX_SENTENCE_CHARS, "500");
        DEFAULTS.put(CORRECTION_GLOSSARY_MAX_CHARS, "12000");

        DEFAULTS.put(PROCESSING_WINDOW_START, "20:00");
        DEFAULTS.put(PROCESSING_WINDOW_END, "06:00");

        // Kuerzere Segmente als frueher (30 Min): bessere Whisper-Qualitaet und
        // weniger Verlust bei einem korrupten Segment.
        DEFAULTS.put(RECORDING_SEGMENT_MINUTES, "10");
        DEFAULTS.put(RECORDING_MP3_BITRATE, "192k");
        DEFAULTS.put(RECORDING_MIN_AUDIO_BYTES, "8000");

        DEFAULTS.put(BOT_CHAT_START_COMMAND, "STARTRECORDING");
        DEFAULTS.put(BOT_CHAT_STOP_COMMAND, "STOPRECORDING");
        DEFAULTS.put(BOT_SEND_CHAT_WARNING, "true");
        DEFAULTS.put(BOT_WARN_MESSAGE, DEFAULT_WARN_MESSAGE);
        DEFAULTS.put(BOT_RECORD_MIN_OTHERS, "1");
        DEFAULTS.put(BOT_CHECK_INTERVAL_MS, "5000");
        DEFAULTS.put(BOT_KEEPALIVE_ENABLED, "true");
        DEFAULTS.put(BOT_KEEPALIVE_INTERVAL_MS, "240000");
        DEFAULTS.put(BOT_KEEPALIVE_MESSAGE, "ping");
        DEFAULTS.put(BOT_KEEPALIVE_PREFIX, "[KEEPALIVE]");
        DEFAULTS.put(BOT_AUTO_RECONNECT, "true");
        DEFAULTS.put(BOT_RECONNECT_MAX_ATTEMPTS, "-1");
        DEFAULTS.put(BOT_RECONNECT_BACKOFF_BASE_MS, "5000");
        DEFAULTS.put(BOT_RECONNECT_BACKOFF_FACTOR, "1.5");
        DEFAULTS.put(BOT_ALLOWED_URL_HOSTS, "");

        DEFAULTS.put(CAPTURE_ENABLED, "true");
        // 8 GB reichen fuer mehrere Stunden in Standardqualitaet und verhindern,
        // dass eine vergessene Aufnahme die Platte fuellt.
        DEFAULTS.put(CAPTURE_MAX_MEGABYTES, "8192");
        DEFAULTS.put(CAPTURE_STALE_MINUTES, "5");

        DEFAULTS.put(SHARING_PUBLIC_LINKS, "true");

        DEFAULTS.put(CLEANUP_ENABLED, "true");
        DEFAULTS.put(CLEANUP_OLDER_THAN_DAYS, "90");
    }

    private final AppSettingRepo repo;

    public SettingsService(AppSettingRepo repo) {
        this.repo = repo;
    }

    public static Map<String, String> defaults() {
        return Map.copyOf(DEFAULTS);
    }

    @Transactional(readOnly = true)
    public String get(String key) {
        String def = DEFAULTS.get(key);
        if (def == null) throw new IllegalArgumentException("Unbekannter Einstellungsschluessel: " + key);
        return repo.findById(key).map(AppSetting::getValue).filter(v -> v != null && !v.isBlank()).orElse(def);
    }

    public int getInt(String key) { return Integer.parseInt(get(key).trim()); }
    public long getLong(String key) { return Long.parseLong(get(key).trim()); }
    public double getDouble(String key) { return Double.parseDouble(get(key).trim()); }
    public boolean getBool(String key) { return Boolean.parseBoolean(get(key).trim()); }

    /** Alle Einstellungen (Defaults + Ueberschreibungen) fuer die Admin-Oberflaeche. */
    @Transactional(readOnly = true)
    public Map<String, String> getAll() {
        Map<String, String> merged = new LinkedHashMap<>(DEFAULTS);
        for (AppSetting s : repo.findAll()) {
            if (merged.containsKey(s.getKey()) && s.getValue() != null) {
                merged.put(s.getKey(), s.getValue());
            }
        }
        return merged;
    }

    @Transactional
    public void update(Map<String, String> changes) {
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String key = e.getKey();
            if (!DEFAULTS.containsKey(key)) {
                throw new IllegalArgumentException("Unbekannter Einstellungsschluessel: " + key);
            }
            validate(key, e.getValue());
            AppSetting setting = repo.findById(key).orElse(new AppSetting(key, null));
            setting.setValue(e.getValue());
            repo.save(setting);
        }
    }

    private void validate(String key, String value) {
        if (value == null) throw new IllegalArgumentException("Wert fuer " + key + " darf nicht null sein");
        try {
            switch (key) {
                case WHISPER_TIMEOUT_SEC, WHISPER_RETRY_ATTEMPTS, WHISPER_RETRY_BASE_MS,
                     LLM_MAX_TOKENS, LLM_TIMEOUT_SEC, LLM_RETRY_ATTEMPTS,
                     LLM_RETRY_BASE_MS, SUMMARY_CHUNK_CHARS, SUMMARY_MIN_AUDIO_MS,
                     SUMMARY_MIN_TRANSCRIPT_CHARS, SUMMARY_MIN_CHAT_CHARS,
                     RECORDING_SEGMENT_MINUTES, RECORDING_MIN_AUDIO_BYTES,
                     CORRECTION_CHUNK_CHARS, CORRECTION_MAX_SENTENCE_CHARS,
                     CORRECTION_GLOSSARY_MAX_CHARS,
                     BOT_RECORD_MIN_OTHERS, BOT_CHECK_INTERVAL_MS, BOT_KEEPALIVE_INTERVAL_MS,
                     BOT_RECONNECT_MAX_ATTEMPTS, BOT_RECONNECT_BACKOFF_BASE_MS,
                     CAPTURE_MAX_MEGABYTES, CAPTURE_STALE_MINUTES,
                     CLEANUP_OLDER_THAN_DAYS -> Long.parseLong(value.trim());
                case LLM_TEMPERATURE, BOT_RECONNECT_BACKOFF_FACTOR -> Double.parseDouble(value.trim());
                case WHISPER_VAD_FILTER, WHISPER_DIARIZE, LLM_DISABLE_THINKING,
                     BOT_SEND_CHAT_WARNING, BOT_KEEPALIVE_ENABLED,
                     BOT_AUTO_RECONNECT, CAPTURE_ENABLED, CORRECTION_ENABLED, CLEANUP_ENABLED,
                     SHARING_PUBLIC_LINKS -> {
                    if (!value.trim().equalsIgnoreCase("true") && !value.trim().equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException("erwartet true/false");
                    }
                }
                case PROCESSING_WINDOW_START, PROCESSING_WINDOW_END -> java.time.LocalTime.parse(value.trim());
                case WHISPER_PROVIDER -> {
                    if (!value.trim().equalsIgnoreCase("local") && !value.trim().equalsIgnoreCase("openai")) {
                        throw new IllegalArgumentException("erwartet local/openai");
                    }
                }
                default -> { /* freie Textwerte */ }
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Ungueltiger Wert fuer " + key + ": " + value);
        }
    }
}
