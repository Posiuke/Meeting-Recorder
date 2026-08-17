package bbbbot.media;

import bbbbot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * webm→mp3-Transkodierung mit den Stabilitaets-Massnahmen des alten Bots
 * (TRANSCODE_AND_CLEANUP.md): Datei-Stabilitaets-Check, ffprobe-Vorvalidierung,
 * tolerante ffmpeg-Flags, Retry mit Backoff und failed/-Ablage.
 */
@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    private static final int STABILITY_CHECK_SECONDS = 5;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MS = 2000;

    private final AppProperties props;

    public FfmpegService(AppProperties props) {
        this.props = props;
    }

    public record TranscodeResult(boolean success, Path mp3Path, Long durationMs, String error) {}

    /**
     * Transkodiert ein webm-Segment nach MP3. Wartet zunaechst, bis die Datei
     * nicht mehr waechst; validiert mit ffprobe; bei Fehlern Retry und Ablage
     * unter failed/.
     */
    public TranscodeResult transcodeWebmToMp3(Path webm, Path mp3, String bitrate) {
        try {
            if (!waitForFileStable(webm, STABILITY_CHECK_SECONDS, 60)) {
                return failWithArchive(webm, "Datei wurde nicht stabil (wird evtl. noch geschrieben)");
            }
            String probeError = validateWithFfprobe(webm);
            if (probeError != null) {
                log.warn("ffprobe-Vorvalidierung fehlgeschlagen fuer {}: {}", webm, probeError);
            }

            String lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                ProcessResult result = run(List.of(
                        props.getMedia().getFfmpegPath(), "-y",
                        "-fflags", "+genpts",
                        "-probesize", "50M",
                        "-analyzeduration", "100M",
                        "-i", webm.toString(),
                        "-vn",
                        "-c:a", "libmp3lame",
                        "-b:a", bitrate,
                        "-ar", "48000",
                        mp3.toString()
                ), 600);
                if (result.exitCode() == 0 && Files.exists(mp3) && Files.size(mp3) > 0) {
                    Long duration = probeDurationMs(mp3);
                    return new TranscodeResult(true, mp3, duration, null);
                }
                lastError = "ffmpeg exit=" + result.exitCode() + ": " + tail(result.stderr(), 500);
                log.warn("Transkodierung fehlgeschlagen (Versuch {}/{}): {}", attempt, MAX_ATTEMPTS, lastError);
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(RETRY_BASE_MS * (1L << (attempt - 1)));
                }
            }
            return failWithArchive(webm, lastError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TranscodeResult(false, null, null, "Unterbrochen");
        } catch (IOException e) {
            return new TranscodeResult(false, null, null, "IO-Fehler: " + e.getMessage());
        }
    }

    public record SplitResult(boolean success, List<Path> parts, String error) {}

    /**
     * Transkodiert eine hochgeladene Audio-/Videodatei in MP3-Segmente fester
     * Laenge (kuerzere Dateien = bessere Whisper-Qualitaet, kein Timeout/VRAM-
     * Problem bei stundenlangen Aufnahmen). Enthaelt die Datei mehrere
     * Tonspuren (z.B. OBS-Mitschnitt: Mikrofon + Systemton), werden ALLE
     * Spuren gemischt - ffmpeg wuerde sonst nur eine einzige (unter Umstaenden
     * die stumme) Spur uebernehmen.
     */
    public SplitResult transcodeToMp3Segments(Path source, Path outDir, int segmentSeconds, String bitrate) {
        try {
            List<String> audioStreams = probeStreams(source, "a", "stream=codec_name").stream()
                    .filter(l -> !l.isBlank()).toList();
            if (audioStreams.isEmpty()) {
                return new SplitResult(false, List.of(), "Datei enthaelt keine Tonspur");
            }
            List<String> cmd = new ArrayList<>(List.of(props.getMedia().getFfmpegPath(), "-y",
                    "-fflags", "+genpts",
                    "-probesize", "50M",
                    "-analyzeduration", "100M",
                    "-i", source.toString()));
            if (audioStreams.size() > 1) {
                StringBuilder inputs = new StringBuilder();
                for (int i = 0; i < audioStreams.size(); i++) inputs.append("[0:a:").append(i).append(']');
                cmd.addAll(List.of(
                        "-filter_complex",
                        inputs + "amix=inputs=" + audioStreams.size() + ":duration=longest:normalize=0[aout]",
                        "-map", "[aout]"));
                log.info("{} Tonspuren in {} werden gemischt", audioStreams.size(), source.getFileName());
            } else {
                cmd.addAll(List.of("-map", "0:a:0"));
            }
            cmd.addAll(List.of(
                    "-vn",
                    "-c:a", "libmp3lame", "-b:a", bitrate, "-ar", "48000",
                    "-f", "segment", "-segment_time", String.valueOf(segmentSeconds),
                    "-reset_timestamps", "1",
                    outDir.resolve("segment_%03d.mp3").toString()));
            ProcessResult result = run(cmd, 7200);
            List<Path> parts;
            try (var files = Files.list(outDir)) {
                parts = files.filter(p -> p.getFileName().toString().matches("segment_\\d{3}\\.mp3"))
                        .sorted().toList();
            }
            if (result.exitCode() == 0 && !parts.isEmpty()) {
                return new SplitResult(true, parts, null);
            }
            return new SplitResult(false, List.of(),
                    "ffmpeg exit=" + result.exitCode() + ": " + tail(result.stderr(), 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SplitResult(false, List.of(), "Unterbrochen");
        } catch (IOException e) {
            return new SplitResult(false, List.of(), "IO-Fehler: " + e.getMessage());
        }
    }

    public record MuxResult(boolean success, Path mp4Path, String error) {}

    /**
     * Muxt die aufgezeichnete Browser-Ansicht (VP8-WebM-Teile aus Playwright) mit
     * dem gemischten Meeting-Audio (MP3-Segmente) zu einer H.264/AAC-MP4.
     * Mehrere Teile werden zuvor per concat-Demuxer zusammengefuehrt.
     *
     * <p>Playwright zeichnet den gesamten Browser-Kontext auf (schon vor dem
     * Aufnahmestart). {@code videoOffsetMs} ist der Vorlauf zwischen Video-/Kontext-
     * start und Aufnahmestart; das Video wird um diesen Betrag vorne beschnitten,
     * sodass Video und Audio bei t=0 synchron am Aufnahmestart beginnen. Das Ende
     * bindet {@code -shortest} an die (kuerzere) Audiolaenge.
     */
    public MuxResult muxToMp4(List<Path> videoParts, List<Path> audioParts, Path outMp4, long videoOffsetMs) {
        List<Path> temps = new ArrayList<>();
        try {
            List<Path> videos = videoParts.stream().filter(p -> p != null && Files.exists(p)).toList();
            if (videos.isEmpty()) {
                return new MuxResult(false, null, "Keine Video-Daten vorhanden");
            }
            List<Path> audios = audioParts.stream().filter(p -> p != null && Files.exists(p)).toList();

            Path video = videos.size() == 1 ? videos.get(0)
                    : concatCopy(videos, outMp4.resolveSibling("video-concat.webm"), temps);
            Path audio = audios.isEmpty() ? null
                    : (audios.size() == 1 ? audios.get(0)
                            : concatCopy(audios, outMp4.resolveSibling("audio-concat.mp3"), temps));

            List<String> cmd = new ArrayList<>(List.of(props.getMedia().getFfmpegPath(), "-y"));
            // Video-Vorlauf abschneiden (Input-Seeking vor -i, bei Re-Encode genau genug).
            if (videoOffsetMs > 0) {
                cmd.add("-ss");
                cmd.add(String.format(java.util.Locale.ROOT, "%.3f", videoOffsetMs / 1000.0));
            }
            cmd.add("-i"); cmd.add(video.toString());
            if (audio != null) { cmd.add("-i"); cmd.add(audio.toString()); }
            cmd.addAll(List.of(
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p"));
            if (audio != null) {
                cmd.addAll(List.of("-c:a", "aac", "-b:a", "128k", "-shortest"));
            }
            cmd.addAll(List.of("-movflags", "+faststart", outMp4.toString()));

            ProcessResult result = run(cmd, 3600);
            if (result.exitCode() == 0 && Files.exists(outMp4) && Files.size(outMp4) > 0) {
                return new MuxResult(true, outMp4, null);
            }
            return new MuxResult(false, null, "ffmpeg exit=" + result.exitCode() + ": " + tail(result.stderr(), 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MuxResult(false, null, "Unterbrochen");
        } catch (IOException e) {
            return new MuxResult(false, null, "IO-Fehler: " + e.getMessage());
        } finally {
            for (Path t : temps) {
                try { Files.deleteIfExists(t); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Codec des ersten echten Video-Streams (Cover-Art wie in MP3s zaehlt nicht),
     * oder null wenn die Datei kein Video enthaelt bzw. nicht lesbar ist.
     */
    public String videoStreamCodec(Path file) {
        for (String line : probeStreams(file, "v", "stream=codec_name:stream_disposition=attached_pic")) {
            String[] parts = line.trim().split(",");
            if (parts.length == 0 || parts[0].isBlank()) continue;
            boolean attachedPic = parts.length > 1 && "1".equals(parts[1].trim());
            if (!attachedPic) return parts[0].trim();
        }
        return null;
    }

    /** Codec des ersten Audio-Streams, oder null. */
    public String audioStreamCodec(Path file) {
        for (String line : probeStreams(file, "a", "stream=codec_name")) {
            String codec = line.trim().split(",")[0].trim();
            if (!codec.isBlank()) return codec;
        }
        return null;
    }

    private List<String> probeStreams(Path file, String streamSelector, String entries) {
        try {
            ProcessResult result = run(List.of(
                    props.getMedia().getFfprobePath(), "-v", "error",
                    "-select_streams", streamSelector,
                    "-show_entries", entries,
                    "-of", "csv=p=0",
                    file.toString()
            ), 60);
            if (result.exitCode() == 0) {
                return List.of(result.stdout().split("\\R"));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("Stream-Probe fehlgeschlagen fuer {}: {}", file, e.getMessage());
        }
        return List.of();
    }

    /**
     * Stellt eine hochgeladene Videodatei als browser-abspielbare MP4 bereit.
     * H.264-Video mit AAC-/MP3-Ton wird ohne Neukodierung nur umgepackt
     * (schnell, verlustfrei); alle anderen Codecs werden zu H.264/AAC kodiert.
     */
    public MuxResult convertToMp4(Path source, Path outMp4) {
        try {
            String videoCodec = videoStreamCodec(source);
            String audioCodec = audioStreamCodec(source);
            boolean remuxable = "h264".equals(videoCodec)
                    && (audioCodec == null || "aac".equals(audioCodec) || "mp3".equals(audioCodec));
            if (remuxable) {
                ProcessResult result = run(List.of(props.getMedia().getFfmpegPath(), "-y",
                        "-i", source.toString(),
                        "-map", "0:v:0", "-map", "0:a:0?",
                        "-c", "copy",
                        "-movflags", "+faststart",
                        outMp4.toString()), 1800);
                if (result.exitCode() == 0 && Files.exists(outMp4) && Files.size(outMp4) > 0) {
                    return new MuxResult(true, outMp4, null);
                }
                log.warn("Remux von {} fehlgeschlagen, versuche Neukodierung: {}",
                        source.getFileName(), tail(result.stderr(), 300));
            }
            ProcessResult result = run(List.of(props.getMedia().getFfmpegPath(), "-y",
                    "-i", source.toString(),
                    "-map", "0:v:0", "-map", "0:a:0?",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart",
                    outMp4.toString()), 7200);
            if (result.exitCode() == 0 && Files.exists(outMp4) && Files.size(outMp4) > 0) {
                return new MuxResult(true, outMp4, null);
            }
            return new MuxResult(false, null, "ffmpeg exit=" + result.exitCode() + ": " + tail(result.stderr(), 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MuxResult(false, null, "Unterbrochen");
        } catch (IOException e) {
            return new MuxResult(false, null, "IO-Fehler: " + e.getMessage());
        }
    }

    /**
     * Fuegt die MP3-Segmente einer Aufnahme zu einer durchgehenden Datei
     * zusammen. Alle Segmente stammen aus der eigenen Transkodierung (gleicher
     * Codec, gleiche Abtastrate), deshalb genuegt der concat-Demuxer mit
     * {@code -c copy}: kein erneutes Kodieren, kein Qualitaetsverlust und
     * Sekunden statt Minuten Laufzeit.
     */
    public TranscodeResult concatMp3(List<Path> parts, Path out) {
        List<Path> existing = parts.stream().filter(p -> p != null && Files.exists(p)).toList();
        if (existing.isEmpty()) {
            return new TranscodeResult(false, null, null, "Keine Audio-Segmente vorhanden");
        }
        List<Path> temps = new ArrayList<>();
        try {
            Path listFile = writeConcatList(existing, out);
            temps.add(listFile);
            ProcessResult result = run(List.of(props.getMedia().getFfmpegPath(), "-y",
                    "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                    "-c", "copy", out.toString()), 1800);
            if (result.exitCode() == 0 && Files.exists(out) && Files.size(out) > 0) {
                return new TranscodeResult(true, out, probeDurationMs(out), null);
            }
            return new TranscodeResult(false, null, null,
                    "ffmpeg exit=" + result.exitCode() + ": " + tail(result.stderr(), 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TranscodeResult(false, null, null, "Unterbrochen");
        } catch (IOException e) {
            return new TranscodeResult(false, null, null, "IO-Fehler: " + e.getMessage());
        } finally {
            for (Path t : temps) {
                try { Files.deleteIfExists(t); } catch (IOException ignored) {}
            }
        }
    }

    private Path writeConcatList(List<Path> parts, Path out) throws IOException {
        Path listFile = out.resolveSibling(out.getFileName() + ".list.txt");
        StringBuilder sb = new StringBuilder();
        for (Path p : parts) {
            sb.append("file '").append(p.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
        }
        Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);
        return listFile;
    }

    private Path concatCopy(List<Path> parts, Path out, List<Path> temps)
            throws IOException, InterruptedException {
        Path listFile = writeConcatList(parts, out);
        temps.add(listFile);
        temps.add(out);
        run(List.of(props.getMedia().getFfmpegPath(), "-y",
                "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                "-c", "copy", out.toString()), 1800);
        return out;
    }

    /** Dauer einer Audiodatei in Millisekunden (ffprobe), oder null. */
    public Long probeDurationMs(Path audio) {
        try {
            ProcessResult result = run(List.of(
                    props.getMedia().getFfprobePath(), "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    audio.toString()
            ), 60);
            if (result.exitCode() == 0) {
                String out = result.stdout().trim();
                if (!out.isEmpty()) {
                    return (long) (Double.parseDouble(out) * 1000);
                }
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("probeDurationMs fehlgeschlagen fuer {}: {}", audio, e.getMessage());
        }
        return null;
    }

    /** Erzeugt eine kurze stille MP3 (fuer als leer erkannte Segmente, optional). */
    public boolean createSilentMp3(Path mp3, int seconds) {
        try {
            ProcessResult result = run(List.of(
                    props.getMedia().getFfmpegPath(), "-y",
                    "-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono",
                    "-t", String.valueOf(seconds),
                    "-c:a", "libmp3lame", "-b:a", "64k",
                    mp3.toString()
            ), 60);
            return result.exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Wartet, bis die Dateigroesse fuer stableSeconds unveraendert bleibt. */
    boolean waitForFileStable(Path file, int stableSeconds, int maxWaitSeconds) throws IOException, InterruptedException {
        long lastSize = -1;
        int stableFor = 0;
        for (int waited = 0; waited <= maxWaitSeconds; waited++) {
            long size = Files.exists(file) ? Files.size(file) : -1;
            if (size == lastSize && size >= 0) {
                stableFor++;
                if (stableFor >= stableSeconds) return true;
            } else {
                stableFor = 0;
            }
            lastSize = size;
            Thread.sleep(1000);
        }
        return false;
    }

    private String validateWithFfprobe(Path file) {
        try {
            ProcessResult result = run(List.of(
                    props.getMedia().getFfprobePath(), "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString()
            ), 60);
            return result.exitCode() == 0 ? null : tail(result.stderr(), 300);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return e.getMessage();
        }
    }

    private TranscodeResult failWithArchive(Path webm, String error) {
        try {
            Path failedDir = webm.getParent().resolve("failed");
            Files.createDirectories(failedDir);
            Path target = failedDir.resolve("failed-" + webm.getFileName());
            Files.copy(webm, target, StandardCopyOption.REPLACE_EXISTING);
            log.error("Segment zur Analyse abgelegt: {} ({})", target, error);
        } catch (IOException e) {
            log.error("failed/-Ablage fehlgeschlagen: {}", e.getMessage());
        }
        return new TranscodeResult(false, null, null, error);
    }

    public record ProcessResult(int exitCode, String stdout, String stderr) {}

    public ProcessResult run(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        // Streams parallel konsumieren, sonst blockiert der Prozess bei vollem Pipe-Puffer
        var outFuture = readAllAsync(process.getInputStream());
        var errFuture = readAllAsync(process.getErrorStream());
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Prozess-Timeout nach " + timeoutSeconds + "s: " + command.get(0));
        }
        try {
            return new ProcessResult(process.exitValue(),
                    new String(outFuture.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8),
                    new String(errFuture.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("Prozess-Ausgabe konnte nicht gelesen werden: " + e.getMessage(), e);
        }
    }

    private static java.util.concurrent.CompletableFuture<byte[]> readAllAsync(java.io.InputStream in) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (in) {
                return in.readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
    }

    private static String tail(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(s.length() - maxChars);
    }
}
