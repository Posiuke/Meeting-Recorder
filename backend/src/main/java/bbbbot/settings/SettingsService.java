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
    /**
     * Cloud-Modell fuer Aufnahmen MIT Sprechererkennung. Das normale Modell
     * (whisper.openaiModel) kann keine Sprecher unterscheiden; ist die
     * Sprechererkennung gewaehlt, geht die Datei an dieses Modell.
     */
    public static final String WHISPER_OPENAI_DIARIZE_MODEL = "whisper.openaiDiarizeModel";
    public static final String WHISPER_LANGUAGE = "whisper.language";
    public static final String WHISPER_OUTPUT = "whisper.output";
    public static final String WHISPER_VAD_FILTER = "whisper.vadFilter";
    public static final String WHISPER_DIARIZE = "whisper.diarize";
    public static final String WHISPER_INITIAL_PROMPT = "whisper.initialPrompt";
    public static final String WHISPER_TIMEOUT_SEC = "whisper.timeoutSec";
    public static final String WHISPER_RETRY_ATTEMPTS = "whisper.retryAttempts";
    public static final String WHISPER_RETRY_BASE_MS = "whisper.retryBaseMs";

    /**
     * LLM-Anbieter: "local" = eigener OpenAI-kompatibler Server im Intranet (vLLM,
     * llama.cpp), "openai" = oeffentliche Cloud-API (OpenAI oder kompatibel). Die
     * Cloud bekommt eigene Felder fuer Adresse, Key und Modell - so bleibt die
     * lokale Konfiguration beim Hin- und Herschalten erhalten.
     */
    public static final String LLM_PROVIDER = "llm.provider";
    public static final String LLM_BASE_URL = "llm.baseUrl";
    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_API_KEY = "llm.apiKey";
    public static final String LLM_OPENAI_URL = "llm.openaiUrl";
    public static final String LLM_OPENAI_API_KEY = "llm.openaiApiKey";
    public static final String LLM_OPENAI_MODEL = "llm.openaiModel";
    /**
     * Denkaufwand ({@code reasoning_effort}) fuer Reasoning-Modelle der Cloud.
     * "off" = nicht mitschicken (fuer Modelle ohne Nachdenken). Lehnt ein Modell
     * den Parameter ab, laesst der Client ihn von selbst weg.
     */
    public static final String LLM_OPENAI_REASONING_EFFORT = "llm.openaiReasoningEffort";
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
    public static final String BOT_AUTO_RECONNECT = "bot.autoReconnect";
    public static final String BOT_RECONNECT_MAX_ATTEMPTS = "bot.reconnectMaxAttempts";
    public static final String BOT_RECONNECT_BACKOFF_BASE_MS = "bot.reconnectBackoffBaseMs";
    public static final String BOT_RECONNECT_BACKOFF_FACTOR = "bot.reconnectBackoffFactor";
    /** SSRF-Schutz: komma-getrennte erlaubte Host-Suffixe fuer die Meeting-URL. Leer = keine Einschraenkung. */
    public static final String BOT_ALLOWED_URL_HOSTS = "bot.allowedUrlHosts";
    /**
     * Anonymer Stopp-Link: Der Bot haengt an seinen Aufnahme-Hinweis einen
     * einmaligen Link, ueber den ein Teilnehmer die Aufnahme verwerfen und den
     * Bot aus dem Raum schicken kann - ohne sich im Chat zu erkennen zu geben.
     */
    public static final String BOT_ANONYMOUS_STOP_ENABLED = "bot.anonymousStopEnabled";
    /**
     * Optionale feste Adresse fuer den Stopp-Link. Leer = automatisch die
     * Adresse, unter der der Bot gestartet bzw. seine Vorlage gespeichert wurde.
     */
    public static final String BOT_PUBLIC_URL = "bot.publicUrl";

    /**
     * Beigefuegte Unterlagen (Tagesordnung, Folien, Papiere) einer Aufnahme. Aus =
     * keine neuen Unterlagen, und vorhandene gehen NICHT mehr in den Prompt ein -
     * ein Ausschalten ist damit auch eine Datenschutz-Notbremse.
     */
    public static final String DOCUMENTS_ENABLED = "documents.enabled";
    /** Obergrenze fuer eine einzelne Unterlage in Megabyte (Plattenschutz). */
    public static final String DOCUMENTS_MAX_MEGABYTES = "documents.maxMegabytes";
    /**
     * Basis-URL des Apache-Tika-Servers fuer PDF, Office-Dateien und Scans. Leer =
     * nur Text- und Markdown-Dateien lassen sich auswerten. OCR macht Tika (dort
     * muss tesseract installiert sein) - dieser Server bringt keine eigene mit.
     */
    public static final String DOCUMENTS_TIKA_URL = "documents.tikaUrl";
    /** Zeitlimit fuer eine Tika-Anfrage; OCR eines mehrseitigen Scans braucht Minuten. */
    public static final String DOCUMENTS_TIKA_TIMEOUT_SEC = "documents.tikaTimeoutSec";
    /**
     * OCR-Strategie fuer PDFs, als Kopfzeile an Tika: auto (nur wenn kaum Text
     * eingebettet ist), no_ocr, ocr_only, ocr_and_text_extraction.
     */
    public static final String DOCUMENTS_OCR_STRATEGY = "documents.ocrStrategy";
    /** Sprache(n) fuer die OCR in Tika (tesseract-Sprachkuerzel, z.B. deu oder deu+eng). */
    public static final String DOCUMENTS_OCR_LANGUAGE = "documents.ocrLanguage";
    /** Zeichen je Unterlage im Prompt; 0 = unbegrenzt. Verhindert, dass ein dickes PDF alle anderen verdraengt. */
    public static final String DOCUMENTS_MAX_CHARS_PER_DOCUMENT = "documents.maxCharsPerDocument";
    /**
     * Obergrenze fuer den gesamten Unterlagen-Block im Prompt; 0 = unbegrenzt. Der
     * Block geht in JEDEN Auswertungsschritt ein und kostet dort Kontext.
     */
    public static final String DOCUMENTS_PROMPT_MAX_CHARS = "documents.promptMaxChars";

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

    /**
     * Namensvorschlaege fuer erkannte Sprecher (Sprechanzeige des Bots und/oder
     * LLM-Auswertung des Gespraechs). Aus = Sprecher bleiben "Sprecher 1/2/...".
     */
    public static final String SPEAKERS_NAME_SUGGESTIONS = "speakers.nameSuggestions";
    /** Sichere Vorschlaege (hohe Sicherheit) automatisch als Namen uebernehmen. */
    public static final String SPEAKERS_AUTO_APPLY = "speakers.autoApply";
    /**
     * Der Bot protokolliert die Sprechanzeige von BBB (wer gerade spricht) - die
     * zuverlaessigste Quelle fuer Namen bei Bot-Aufnahmen.
     */
    public static final String SPEAKERS_BBB_ACTIVITY = "speakers.bbbActivity";

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
        DEFAULTS.put(WHISPER_OPENAI_DIARIZE_MODEL, "gpt-4o-transcribe-diarize");
        DEFAULTS.put(WHISPER_LANGUAGE, "de");
        DEFAULTS.put(WHISPER_OUTPUT, "json");
        DEFAULTS.put(WHISPER_VAD_FILTER, "true");
        // Sprechererkennung FREISCHALTEN: Nutzer koennen sie dann pro Aufnahme/Upload
        // waehlen. Lokal benoetigt sie ASR_ENGINE=whisperx (docs/WHISPER_DIARIZATION.md),
        // in der Cloud das Modell aus whisper.openaiDiarizeModel.
        DEFAULTS.put(WHISPER_DIARIZE, "false");
        DEFAULTS.put(WHISPER_INITIAL_PROMPT, "");
        DEFAULTS.put(WHISPER_TIMEOUT_SEC, "600");
        DEFAULTS.put(WHISPER_RETRY_ATTEMPTS, "2");
        DEFAULTS.put(WHISPER_RETRY_BASE_MS, "2000");

        DEFAULTS.put(LLM_PROVIDER, "local");
        DEFAULTS.put(LLM_BASE_URL, "http://localhost:11434/v1");
        DEFAULTS.put(LLM_MODEL, "Qwen3.5-122B");
        DEFAULTS.put(LLM_API_KEY, "");
        DEFAULTS.put(LLM_OPENAI_URL, "https://api.openai.com/v1");
        DEFAULTS.put(LLM_OPENAI_API_KEY, "");
        DEFAULTS.put(LLM_OPENAI_MODEL, "gpt-4o-mini");
        // Niedrig: Glaetten und Zusammenfassen brauchen kein langes Nachdenken, und
        // das Nachdenken zehrt am selben Token-Budget wie die Antwort.
        DEFAULTS.put(LLM_OPENAI_REASONING_EFFORT, "low");
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

        DEFAULTS.put(DOCUMENTS_ENABLED, "true");
        DEFAULTS.put(DOCUMENTS_MAX_MEGABYTES, "25");
        DEFAULTS.put(DOCUMENTS_TIKA_URL, "");
        DEFAULTS.put(DOCUMENTS_TIKA_TIMEOUT_SEC, "300");
        DEFAULTS.put(DOCUMENTS_OCR_STRATEGY, "auto");
        DEFAULTS.put(DOCUMENTS_OCR_LANGUAGE, "deu");
        // Fair aufgeteilt statt "ein PDF nimmt alles": je Unterlage 4000 Zeichen,
        // zusammen 12000. Der Block geht in jeden Schritt ein.
        DEFAULTS.put(DOCUMENTS_MAX_CHARS_PER_DOCUMENT, "4000");
        DEFAULTS.put(DOCUMENTS_PROMPT_MAX_CHARS, "12000");

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
        DEFAULTS.put(BOT_AUTO_RECONNECT, "true");
        DEFAULTS.put(BOT_RECONNECT_MAX_ATTEMPTS, "-1");
        DEFAULTS.put(BOT_RECONNECT_BACKOFF_BASE_MS, "5000");
        DEFAULTS.put(BOT_RECONNECT_BACKOFF_FACTOR, "1.5");
        DEFAULTS.put(BOT_ALLOWED_URL_HOSTS, "");
        DEFAULTS.put(BOT_ANONYMOUS_STOP_ENABLED, "false");
        DEFAULTS.put(BOT_PUBLIC_URL, "");

        DEFAULTS.put(CAPTURE_ENABLED, "true");
        // 8 GB reichen fuer mehrere Stunden in Standardqualitaet und verhindern,
        // dass eine vergessene Aufnahme die Platte fuellt.
        DEFAULTS.put(CAPTURE_MAX_MEGABYTES, "8192");
        DEFAULTS.put(CAPTURE_STALE_MINUTES, "5");

        DEFAULTS.put(SHARING_PUBLIC_LINKS, "true");

        DEFAULTS.put(SPEAKERS_NAME_SUGGESTIONS, "true");
        DEFAULTS.put(SPEAKERS_AUTO_APPLY, "false");
        DEFAULTS.put(SPEAKERS_BBB_ACTIVITY, "true");

        DEFAULTS.put(CLEANUP_ENABLED, "true");
        DEFAULTS.put(CLEANUP_OLDER_THAN_DAYS, "90");
    }

    /** Art eines Einstellungswerts - steuert Validierung und Eingabefeld im Admin-Bereich. */
    public enum SettingType { BOOLEAN, INTEGER, DECIMAL, CHOICE, TIME, URL, LANGUAGE, SECRET, TEXT, MULTILINE }

    /**
     * Beschreibung eines Einstellungswerts. min/max gelten fuer Zahlen, options
     * fuer Auswahllisten; optional = ein leerer Wert ist erlaubt (z.B. "keine
     * Tika-Adresse").
     */
    public record SettingSpec(SettingType type, Double min, Double max, java.util.List<String> options,
                              boolean optional) {
        static SettingSpec of(SettingType type) { return new SettingSpec(type, null, null, null, false); }
        static SettingSpec integer(long min, long max) {
            return new SettingSpec(SettingType.INTEGER, (double) min, (double) max, null, false);
        }
        static SettingSpec decimal(double min, double max) {
            return new SettingSpec(SettingType.DECIMAL, min, max, null, false);
        }
        static SettingSpec choice(String... options) {
            return new SettingSpec(SettingType.CHOICE, null, null, java.util.List.of(options), false);
        }
        static SettingSpec optionalUrl() { return new SettingSpec(SettingType.URL, null, null, null, true); }
    }

    private static final long NO_LIMIT = Integer.MAX_VALUE;
    private static final Map<String, SettingSpec> SPECS = new LinkedHashMap<>();
    static {
        SPECS.put(WHISPER_PROVIDER, SettingSpec.choice("local", "openai"));
        SPECS.put(WHISPER_URL, SettingSpec.of(SettingType.URL));
        SPECS.put(WHISPER_OPENAI_URL, SettingSpec.of(SettingType.URL));
        SPECS.put(WHISPER_OPENAI_API_KEY, SettingSpec.of(SettingType.SECRET));
        SPECS.put(WHISPER_LANGUAGE, SettingSpec.of(SettingType.LANGUAGE));
        SPECS.put(WHISPER_OUTPUT, SettingSpec.choice("json", "text", "vtt", "srt", "tsv"));
        SPECS.put(WHISPER_VAD_FILTER, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(WHISPER_DIARIZE, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(WHISPER_INITIAL_PROMPT, SettingSpec.of(SettingType.MULTILINE));
        SPECS.put(WHISPER_TIMEOUT_SEC, SettingSpec.integer(10, 7200));
        SPECS.put(WHISPER_RETRY_ATTEMPTS, SettingSpec.integer(1, 10));
        SPECS.put(WHISPER_RETRY_BASE_MS, SettingSpec.integer(0, 600_000));

        SPECS.put(LLM_PROVIDER, SettingSpec.choice("local", "openai"));
        SPECS.put(LLM_BASE_URL, SettingSpec.of(SettingType.URL));
        SPECS.put(LLM_API_KEY, SettingSpec.of(SettingType.SECRET));
        SPECS.put(LLM_OPENAI_URL, SettingSpec.of(SettingType.URL));
        SPECS.put(LLM_OPENAI_API_KEY, SettingSpec.of(SettingType.SECRET));
        // "off" = reasoning_effort nicht mitschicken
        SPECS.put(LLM_OPENAI_REASONING_EFFORT,
                SettingSpec.choice("off", "none", "minimal", "low", "medium", "high"));
        SPECS.put(LLM_TEMPERATURE, SettingSpec.decimal(0, 2));
        SPECS.put(LLM_MAX_TOKENS, SettingSpec.integer(64, 1_000_000));
        SPECS.put(LLM_DISABLE_THINKING, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(LLM_TIMEOUT_SEC, SettingSpec.integer(10, 7200));
        SPECS.put(LLM_RETRY_ATTEMPTS, SettingSpec.integer(1, 10));
        SPECS.put(LLM_RETRY_BASE_MS, SettingSpec.integer(0, 600_000));

        SPECS.put(SUMMARY_CHUNK_CHARS, SettingSpec.integer(1000, 2_000_000));
        SPECS.put(SUMMARY_SYSTEM_PROMPT, SettingSpec.of(SettingType.MULTILINE));
        SPECS.put(SUMMARY_MIN_AUDIO_MS, SettingSpec.integer(0, 86_400_000));
        SPECS.put(SUMMARY_MIN_TRANSCRIPT_CHARS, SettingSpec.integer(0, NO_LIMIT));
        SPECS.put(SUMMARY_MIN_CHAT_CHARS, SettingSpec.integer(0, NO_LIMIT));

        SPECS.put(CORRECTION_ENABLED, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(CORRECTION_SYSTEM_PROMPT, SettingSpec.of(SettingType.MULTILINE));
        SPECS.put(CORRECTION_CHUNK_CHARS, SettingSpec.integer(500, 200_000));
        SPECS.put(CORRECTION_MAX_SENTENCE_CHARS, SettingSpec.integer(50, 20_000));
        SPECS.put(CORRECTION_GLOSSARY_MAX_CHARS, SettingSpec.integer(0, NO_LIMIT));

        SPECS.put(DOCUMENTS_ENABLED, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(DOCUMENTS_MAX_MEGABYTES, SettingSpec.integer(1, 4096));
        SPECS.put(DOCUMENTS_TIKA_URL, SettingSpec.optionalUrl());
        SPECS.put(DOCUMENTS_TIKA_TIMEOUT_SEC, SettingSpec.integer(10, 7200));
        // Genau die Werte, die Tika als PDF-OCR-Strategie kennt (X-Tika-PDFOcrStrategy).
        SPECS.put(DOCUMENTS_OCR_STRATEGY, SettingSpec.choice("auto", "no_ocr", "ocr_only", "ocr_and_text_extraction"));
        SPECS.put(DOCUMENTS_MAX_CHARS_PER_DOCUMENT, SettingSpec.integer(0, NO_LIMIT));
        SPECS.put(DOCUMENTS_PROMPT_MAX_CHARS, SettingSpec.integer(0, NO_LIMIT));

        SPECS.put(PROCESSING_WINDOW_START, SettingSpec.of(SettingType.TIME));
        SPECS.put(PROCESSING_WINDOW_END, SettingSpec.of(SettingType.TIME));

        SPECS.put(RECORDING_SEGMENT_MINUTES, SettingSpec.integer(1, 120));
        SPECS.put(RECORDING_MP3_BITRATE, SettingSpec.choice("64k", "96k", "128k", "160k", "192k", "256k", "320k"));
        SPECS.put(RECORDING_MIN_AUDIO_BYTES, SettingSpec.integer(0, NO_LIMIT));

        SPECS.put(BOT_SEND_CHAT_WARNING, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(BOT_WARN_MESSAGE, SettingSpec.of(SettingType.MULTILINE));
        SPECS.put(BOT_RECORD_MIN_OTHERS, SettingSpec.integer(0, 1000));
        SPECS.put(BOT_CHECK_INTERVAL_MS, SettingSpec.integer(1000, 600_000));
        SPECS.put(BOT_AUTO_RECONNECT, SettingSpec.of(SettingType.BOOLEAN));
        // -1 = unbegrenzt viele Versuche
        SPECS.put(BOT_RECONNECT_MAX_ATTEMPTS, SettingSpec.integer(-1, 10_000));
        SPECS.put(BOT_RECONNECT_BACKOFF_BASE_MS, SettingSpec.integer(0, 3_600_000));
        SPECS.put(BOT_RECONNECT_BACKOFF_FACTOR, SettingSpec.decimal(1, 10));
        SPECS.put(BOT_ANONYMOUS_STOP_ENABLED, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(BOT_PUBLIC_URL, SettingSpec.optionalUrl());

        SPECS.put(CAPTURE_ENABLED, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(CAPTURE_MAX_MEGABYTES, SettingSpec.integer(1, 1_000_000));
        SPECS.put(CAPTURE_STALE_MINUTES, SettingSpec.integer(1, 1440));

        SPECS.put(SHARING_PUBLIC_LINKS, SettingSpec.of(SettingType.BOOLEAN));

        SPECS.put(SPEAKERS_NAME_SUGGESTIONS, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(SPEAKERS_AUTO_APPLY, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(SPEAKERS_BBB_ACTIVITY, SettingSpec.of(SettingType.BOOLEAN));

        SPECS.put(CLEANUP_ENABLED, SettingSpec.of(SettingType.BOOLEAN));
        SPECS.put(CLEANUP_OLDER_THAN_DAYS, SettingSpec.integer(1, 36_500));
    }

    private final AppSettingRepo repo;

    public SettingsService(AppSettingRepo repo) {
        this.repo = repo;
    }

    public static Map<String, String> defaults() {
        return Map.copyOf(DEFAULTS);
    }

    /** Typbeschreibung je Schluessel (ohne eigenen Eintrag: freier Text). */
    public static Map<String, SettingSpec> schema() {
        Map<String, SettingSpec> all = new LinkedHashMap<>();
        for (String key : DEFAULTS.keySet()) {
            all.put(key, SPECS.getOrDefault(key, SettingSpec.of(SettingType.TEXT)));
        }
        return all;
    }

    @Transactional(readOnly = true)
    public String get(String key) {
        String def = DEFAULTS.get(key);
        if (def == null) throw new IllegalArgumentException("Unbekannter Einstellungsschluessel: " + key);
        return repo.findById(key).map(AppSetting::getValue).filter(v -> v != null && !v.isBlank()).orElse(def);
    }

    /** true, wenn das LLM in der Cloud laeuft ({@code llm.provider = openai}). */
    public boolean isLlmCloud() {
        return "openai".equalsIgnoreCase(get(LLM_PROVIDER).trim());
    }

    /**
     * Standardmodell des gerade gewaehlten Anbieters - das, was ohne Vorgabe der
     * Vorlage verwendet und an der Zusammenfassung vermerkt wird.
     */
    public String llmModel() {
        return get(isLlmCloud() ? LLM_OPENAI_MODEL : LLM_MODEL);
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
        SettingSpec spec = SPECS.getOrDefault(key, SettingSpec.of(SettingType.TEXT));
        String v = value.trim();
        String problem = problemWith(spec, v);
        if (problem != null) {
            throw new IllegalArgumentException("Ungueltiger Wert fuer " + key + ": '" + value + "' (" + problem + ")");
        }
    }

    /** Beschreibung des Problems oder null, wenn der Wert zur Spezifikation passt. */
    static String problemWith(SettingSpec spec, String v) {
        if (v.isEmpty()) {
            // Leer heisst bei Freitext "Standard verwenden" (siehe get); bei
            // typisierten Werten nur, wenn das ausdruecklich vorgesehen ist.
            return switch (spec.type()) {
                case TEXT, MULTILINE, SECRET -> null;
                default -> spec.optional() ? null : "darf nicht leer sein";
            };
        }
        try {
            switch (spec.type()) {
                case BOOLEAN -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) return "erwartet true/false";
                }
                case INTEGER, DECIMAL -> {
                    double number = spec.type() == SettingType.INTEGER ? Long.parseLong(v) : Double.parseDouble(v);
                    if (Double.isNaN(number) || Double.isInfinite(number)) return "keine Zahl";
                    if (spec.min() != null && number < spec.min()) return "Minimum " + formatLimit(spec.min());
                    if (spec.max() != null && number > spec.max()) return "Maximum " + formatLimit(spec.max());
                }
                case CHOICE -> {
                    if (!spec.options().contains(v.toLowerCase(java.util.Locale.ROOT))) {
                        return "erwartet " + String.join("/", spec.options());
                    }
                }
                case TIME -> java.time.LocalTime.parse(v);
                case URL -> {
                    java.net.URI uri = java.net.URI.create(v);
                    if (uri.getHost() == null
                            || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                        return "erwartet http(s)://host";
                    }
                }
                case LANGUAGE -> bbbbot.stt.SttLanguage.normalize(v);
                default -> { /* freie Textwerte */ }
            }
        } catch (RuntimeException ex) {
            return switch (spec.type()) {
                case INTEGER -> "erwartet eine ganze Zahl";
                case DECIMAL -> "erwartet eine Zahl";
                case TIME -> "erwartet HH:MM";
                case URL -> "erwartet http(s)://host";
                case LANGUAGE -> "erwartet einen Sprachcode wie de, en oder auto";
                default -> ex.getMessage();
            };
        }
        return null;
    }

    private static String formatLimit(double limit) {
        return limit == Math.rint(limit) ? String.valueOf((long) limit) : String.valueOf(limit);
    }
}
