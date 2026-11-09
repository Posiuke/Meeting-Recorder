package bbbbot.stt.transcribe;

import bbbbot.config.AppProperties;
import bbbbot.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.transcribe.TranscribeClient;
import software.amazon.awssdk.services.transcribe.model.BadRequestException;
import software.amazon.awssdk.services.transcribe.model.ConflictException;
import software.amazon.awssdk.services.transcribe.model.LanguageCode;
import software.amazon.awssdk.services.transcribe.model.LanguageIdSettings;
import software.amazon.awssdk.services.transcribe.model.MediaFormat;
import software.amazon.awssdk.services.transcribe.model.NotFoundException;
import software.amazon.awssdk.services.transcribe.model.Settings;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;
import software.amazon.awssdk.services.transcribe.model.TranscriptionJob;
import software.amazon.awssdk.services.transcribe.model.TranscriptionJobStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spracherkennung ueber Amazon Transcribe (Stapelauftrag).
 *
 * <p>Ablauf je Datei: kurz in einen S3-Bucket legen, Auftrag starten, auf das
 * Ergebnis warten, Ergebnis-JSON ueber die vorsignierte Adresse holen - und
 * danach Datei UND Auftrag wieder loeschen, damit bei AWS nichts liegen bleibt.
 * Anmeldung ueber die Standardkette des AWS-SDK (auf ECS die Task-Rolle).
 */
@Component
public class AwsTranscriber {

    private static final Logger log = LoggerFactory.getLogger(AwsTranscriber.class);

    /** Transcribe nimmt Dateien bis 4 Stunden an. */
    public static final long MAX_DURATION_MS = 4L * 60 * 60 * 1000 - 60_000;

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    /**
     * Sprachen fuer "automatisch erkennen" - Transcribe braucht dafuer eine
     * Kandidatenliste, wenn ein Vocabulary mitlaufen soll.
     */
    static final List<String> AUTO_CANDIDATES = List.of("de-DE", "en-US", "fr-FR", "es-ES", "it-IT", "nl-NL");

    private final SettingsService settings;
    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final Map<String, TranscribeClient> transcribeClients = new ConcurrentHashMap<>();
    private final Map<String, S3Client> s3Clients = new ConcurrentHashMap<>();

    public AwsTranscriber(SettingsService settings, AppProperties props) {
        this.settings = settings;
        this.props = props;
    }

    /** Ergebnis eines Auftrags; bei Erfolg die Aeusserungen und die (erkannte) Sprache. */
    public record Result(boolean success, List<TranscribeResult.Utterance> utterances, String languageCode,
                         String error) {
        static Result failed(String error) {
            return new Result(false, List.of(), null, error);
        }
    }

    /**
     * @param audio    MP3-Datei (lokal)
     * @param language ISO-Sprache ("de", "en", ...); leer = automatisch erkennen
     * @param diarize  Sprecher unterscheiden
     */
    public Result transcribe(Path audio, String language, boolean diarize) {
        String bucket = bucket();
        if (bucket == null) {
            return Result.failed("Kein S3-Bucket fuer Amazon Transcribe: whisper.awsBucket setzen "
                    + "oder die Ablage auf S3 umstellen (STORAGE_BACKEND=s3)");
        }
        String region = settings.get(SettingsService.WHISPER_AWS_REGION);
        String jobName = "meeting-recorder-" + UUID.randomUUID();
        String key = keyPrefix() + "transcribe-tmp/" + jobName + ".mp3";
        S3Client s3 = s3(region);
        TranscribeClient transcribe = transcribe(region);
        boolean uploaded = false;
        boolean started = false;
        try {
            s3.putObject(b -> b.bucket(bucket).key(key).contentType("audio/mpeg"), RequestBody.fromFile(audio));
            uploaded = true;

            String vocabulary = settings.get(SettingsService.WHISPER_AWS_VOCABULARY).trim();
            StartTranscriptionJobRequest request = request(jobName, "s3://" + bucket + "/" + key, language,
                    diarize, vocabulary);
            try {
                transcribe.startTranscriptionJob(request);
            } catch (BadRequestException e) {
                // Ein noch nicht fertiges oder geloeschtes Vocabulary soll die
                // Spracherkennung nicht verhindern - dann eben ohne.
                if (vocabulary.isEmpty() || !String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("vocabular")) {
                    throw e;
                }
                log.warn("Vocabulary {} nicht nutzbar ({}) - Auftrag ohne Vocabulary", vocabulary, e.getMessage());
                transcribe.startTranscriptionJob(request(jobName, "s3://" + bucket + "/" + key, language, diarize, ""));
            }
            started = true;
            log.info("Transcribe-Auftrag {} gestartet ({}, Sprache {}, Sprecher {})", jobName,
                    audio.getFileName(), language.isBlank() ? "automatisch" : language, diarize);

            TranscriptionJob job = waitFor(transcribe, jobName,
                    Duration.ofSeconds(settings.getInt(SettingsService.WHISPER_TIMEOUT_SEC)));
            if (job.transcriptionJobStatus() != TranscriptionJobStatus.COMPLETED) {
                return Result.failed("Transcribe-Auftrag " + job.transcriptionJobStatusAsString() + ": "
                        + job.failureReason());
            }
            JsonNode json = fetch(job.transcript().transcriptFileUri());
            List<TranscribeResult.Utterance> utterances = TranscribeResult.parse(json);
            String detected = job.languageCodeAsString() != null ? job.languageCodeAsString()
                    : TranscribeResult.languageCode(json);
            return new Result(true, utterances, detected, null);
        } catch (TimeoutException e) {
            return Result.failed(e.getMessage());
        } catch (SdkException | IOException e) {
            return Result.failed("Amazon Transcribe: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Unterbrochen");
        } finally {
            // Nichts bei AWS liegen lassen - weder die Aufnahme noch das Ergebnis.
            if (started) {
                try {
                    transcribe.deleteTranscriptionJob(b -> b.transcriptionJobName(jobName));
                } catch (SdkException e) {
                    log.warn("Transcribe-Auftrag {} konnte nicht geloescht werden: {}", jobName, e.getMessage());
                }
            }
            if (uploaded) {
                try {
                    s3.deleteObject(b -> b.bucket(bucket).key(key));
                } catch (SdkException e) {
                    log.warn("Temporaere Datei s3://{}/{} konnte nicht geloescht werden: {}", bucket, key,
                            e.getMessage());
                }
            }
        }
    }

    StartTranscriptionJobRequest request(String jobName, String mediaUri, String language, boolean diarize,
                                         String vocabulary) {
        StartTranscriptionJobRequest.Builder b = StartTranscriptionJobRequest.builder()
                .transcriptionJobName(jobName)
                .media(m -> m.mediaFileUri(mediaUri))
                .mediaFormat(MediaFormat.MP3);
        Settings.Builder s = Settings.builder();
        boolean anySetting = false;
        if (diarize) {
            s.showSpeakerLabels(true).maxSpeakerLabels(settings.getInt(SettingsService.WHISPER_AWS_MAX_SPEAKERS));
            anySetting = true;
        }
        String code = language == null || language.isBlank() ? null : languageCode(language);
        if (code != null) {
            b.languageCode(code);
            if (!vocabulary.isEmpty()) {
                s.vocabularyName(vocabulary);
                anySetting = true;
            }
        } else {
            b.identifyLanguage(true).languageOptionsWithStrings(AUTO_CANDIDATES);
            String vocabLanguage = vocabularyLanguage(vocabulary);
            if (!vocabulary.isEmpty() && vocabLanguage != null && AUTO_CANDIDATES.contains(vocabLanguage)) {
                b.languageIdSettingsWithStrings(Map.of(vocabLanguage,
                        LanguageIdSettings.builder().vocabularyName(vocabulary).build()));
            }
        }
        if (anySetting) b.settings(s.build());
        return b.build();
    }

    private TranscriptionJob waitFor(TranscribeClient transcribe, String jobName, Duration timeout)
            throws InterruptedException, TimeoutException {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            TranscriptionJob job = transcribe.getTranscriptionJob(b -> b.transcriptionJobName(jobName))
                    .transcriptionJob();
            TranscriptionJobStatus status = job.transcriptionJobStatus();
            if (status == TranscriptionJobStatus.COMPLETED || status == TranscriptionJobStatus.FAILED) return job;
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("Transcribe-Auftrag " + jobName + " nach " + timeout.toSeconds()
                        + " s nicht fertig (whisper.timeoutSec erhoehen)");
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private JsonNode fetch(String uri) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Ergebnis nicht abrufbar (HTTP " + response.statusCode() + ")");
        }
        return mapper.readTree(response.body());
    }

    // ------------------------------------------------------------------
    // Vocabulary aus dem Glossar
    // ------------------------------------------------------------------

    /** Ergebnis der Uebertragung ins Vocabulary. */
    public record VocabularyResult(boolean success, String name, int phrases, int skipped, String message) {}

    /**
     * Legt ein Custom Vocabulary aus den Begriffen an oder aktualisiert es. AWS
     * bereitet es danach einige Minuten vor; bis dahin laufen Auftraege ohne.
     *
     * @param language ISO-Sprache des Vocabulary ("de")
     */
    public VocabularyResult syncVocabulary(Collection<String> terms, String language) {
        String code = languageCode(language == null || language.isBlank() ? "de" : language);
        if (code == null) {
            return new VocabularyResult(false, null, 0, 0, "Sprache " + language + " wird von Transcribe nicht unterstuetzt");
        }
        Set<String> phrases = new LinkedHashSet<>();
        int skipped = 0;
        for (String term : terms) {
            String phrase = vocabularyPhrase(term);
            if (phrase == null) {
                skipped++;
            } else {
                phrases.add(phrase);
            }
        }
        if (phrases.isEmpty()) {
            return new VocabularyResult(false, null, 0, skipped,
                    "Keine verwendbaren Begriffe im gemeinsamen Glossar");
        }
        String name = "meeting-recorder-" + code;
        TranscribeClient transcribe = transcribe(settings.get(SettingsService.WHISPER_AWS_REGION));
        List<String> list = new ArrayList<>(phrases);
        try {
            try {
                transcribe.getVocabulary(b -> b.vocabularyName(name));
                transcribe.updateVocabulary(b -> b.vocabularyName(name).languageCode(code).phrases(list));
            } catch (NotFoundException e) {
                transcribe.createVocabulary(b -> b.vocabularyName(name).languageCode(code).phrases(list));
            } catch (BadRequestException e) {
                // aeltere SDK/Regionen melden ein fehlendes Vocabulary als BadRequest
                if (!String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("couldn't be found")) throw e;
                transcribe.createVocabulary(b -> b.vocabularyName(name).languageCode(code).phrases(list));
            }
        } catch (ConflictException e) {
            return new VocabularyResult(false, name, list.size(), skipped,
                    "Vocabulary wird gerade noch vorbereitet - in einigen Minuten erneut versuchen");
        } catch (SdkException e) {
            return new VocabularyResult(false, name, list.size(), skipped, "Amazon Transcribe: " + e.getMessage());
        }
        return new VocabularyResult(true, name, list.size(), skipped,
                "Vocabulary " + name + " mit " + list.size() + " Begriff(en) uebertragen"
                        + (skipped > 0 ? " (" + skipped + " uebersprungen)" : "")
                        + " - AWS bereitet es einige Minuten vor");
    }

    /**
     * Ein Glossarbegriff als Vocabulary-Phrase: Transcribe erlaubt in der
     * Listenform nur Buchstaben (inkl. Umlaute), Punkt (Abkuerzungen), Apostroph
     * und Bindestrich; Woerter werden mit Bindestrich verbunden. Ziffern und
     * andere Zeichen fuehren dazu, dass der Begriff uebersprungen wird.
     */
    static String vocabularyPhrase(String term) {
        if (term == null) return null;
        String t = term.trim().replaceAll("\\s+", "-");
        if (t.isEmpty() || t.length() > 256) return null;
        if (!t.matches("[\\p{L}.'-]+")) return null;
        if (t.startsWith("-") || t.endsWith("-")) return null;
        return t;
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    /** ISO-Sprache (de, en, pt-br) -> Transcribe-Code (de-DE, en-US, pt-BR); null = unbekannt. */
    static String languageCode(String iso) {
        String l = iso.trim().toLowerCase(Locale.ROOT);
        if (l.contains("-") && l.length() == 5) {
            String candidate = l.substring(0, 2) + "-" + l.substring(3).toUpperCase(Locale.ROOT);
            if (LanguageCode.fromValue(candidate) != LanguageCode.UNKNOWN_TO_SDK_VERSION) return candidate;
        }
        String base = l.split("-")[0];
        String mapped = switch (base) {
            case "de" -> "de-DE";
            case "en" -> "en-US";
            case "fr" -> "fr-FR";
            case "es" -> "es-ES";
            case "it" -> "it-IT";
            case "nl" -> "nl-NL";
            case "pt" -> "pt-PT";
            case "pl" -> "pl-PL";
            case "tr" -> "tr-TR";
            case "ru" -> "ru-RU";
            case "sv" -> "sv-SE";
            case "da" -> "da-DK";
            case "fi" -> "fi-FI";
            case "no", "nb" -> "no-NO";
            case "cs" -> "cs-CZ";
            case "uk" -> "uk-UA";
            case "ar" -> "ar-SA";
            case "zh" -> "zh-CN";
            case "ja" -> "ja-JP";
            case "ko" -> "ko-KR";
            default -> null;
        };
        if (mapped == null || LanguageCode.fromValue(mapped) == LanguageCode.UNKNOWN_TO_SDK_VERSION) return null;
        return mapped;
    }

    /** Sprache eines Vocabulary aus seinem Namen (meeting-recorder-de-DE -> de-DE). */
    static String vocabularyLanguage(String vocabulary) {
        if (vocabulary == null) return null;
        var m = java.util.regex.Pattern.compile("([a-z]{2}-[A-Z]{2})$").matcher(vocabulary);
        return m.find() ? m.group(1) : null;
    }

    /** Bucket fuer die temporaere Datei: eigene Einstellung, sonst der Bucket der S3-Ablage. */
    String bucket() {
        String configured = settings.get(SettingsService.WHISPER_AWS_BUCKET).trim();
        if (!configured.isEmpty()) return configured;
        AppProperties.Storage storage = props.getStorage();
        if ("s3".equalsIgnoreCase(storage.getBackend()) && storage.getS3() != null
                && storage.getS3().getBucket() != null && !storage.getS3().getBucket().isBlank()) {
            return storage.getS3().getBucket().trim();
        }
        return null;
    }

    /** Im Bucket der Ablage unter deren Praefix, damit die Rechte der Task-Rolle passen. */
    private String keyPrefix() {
        if (!settings.get(SettingsService.WHISPER_AWS_BUCKET).isBlank()) return "";
        String prefix = props.getStorage().getS3().getPrefix();
        if (prefix == null || prefix.isBlank()) return "";
        String p = prefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        return p.isEmpty() ? "" : p + "/";
    }

    private TranscribeClient transcribe(String region) {
        Region r = resolveRegion(region);
        return transcribeClients.computeIfAbsent(r.id(), k -> TranscribeClient.builder().region(r)
                .credentialsProvider(DefaultCredentialsProvider.builder().build()).build());
    }

    private S3Client s3(String region) {
        Region r = resolveRegion(region);
        return s3Clients.computeIfAbsent(r.id(), k -> S3Client.builder().region(r)
                .credentialsProvider(DefaultCredentialsProvider.builder().build()).build());
    }

    static Region resolveRegion(String region) {
        if (region != null && !region.isBlank()) return Region.of(region.trim());
        try {
            return new DefaultAwsRegionProviderChain().getRegion();
        } catch (SdkException e) {
            return Region.EU_CENTRAL_1;
        }
    }

    /** Zeitueberschreitung beim Warten auf den Auftrag. */
    static final class TimeoutException extends Exception {
        TimeoutException(String message) {
            super(message);
        }
    }
}
