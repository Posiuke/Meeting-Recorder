package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.media.FfmpegService;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stellt die durchgehende Tonspur einer Aufnahme bereit: Die MP3-Segmente
 * werden zu {@code audio.mp3} im Aufnahme-Verzeichnis zusammengefuegt.
 *
 * <p>Sie ist die Grundlage fuer den Sprung aus dem Transkript in die Aufnahme -
 * mit einer durchgehenden Datei ist die Stelle einfach {@code currentTime} und
 * muss nicht erst auf ein Segment umgerechnet werden - und zugleich der
 * Download der kompletten Aufnahme.
 *
 * <p>Erzeugt wird sie beim ersten Abruf und danach wiederverwendet. Kommen
 * Segmente hinzu oder werden sie neu transkodiert, ist die Datei aelter als das
 * juengste Segment und wird verworfen. Pro Aufnahme laeuft hoechstens ein
 * ffmpeg-Lauf; parallele Abrufe warten auf ihn, statt dieselbe Datei mehrfach
 * zu schreiben.
 */
@Service
public class FullAudioService {

    private static final Logger log = LoggerFactory.getLogger(FullAudioService.class);

    /** Name der zusammengefuegten Datei im Aufnahme-Verzeichnis. */
    static final String FILENAME = "audio.mp3";

    private final RecordingSegmentRepo segmentRepo;
    private final FfmpegService ffmpeg;

    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public FullAudioService(RecordingSegmentRepo segmentRepo, FfmpegService ffmpeg) {
        this.segmentRepo = segmentRepo;
        this.ffmpeg = ffmpeg;
    }

    /**
     * Pfad der durchgehenden Tonspur, notfalls frisch zusammengefuegt.
     *
     * @return leer, wenn die Aufnahme kein verwertbares Audio hat oder das
     *         Zusammenfuegen fehlschlaegt
     */
    public Optional<Path> fullAudio(Recording recording) {
        List<Path> parts = audioParts(recording.getId());
        if (parts.isEmpty()) return Optional.empty();
        // Eine einzelne Datei ist bereits die ganze Aufnahme - nichts zu tun.
        if (parts.size() == 1) return Optional.of(parts.get(0));

        Path target = Path.of(recording.getDirectory()).resolve(FILENAME);
        ReentrantLock lock = locks.computeIfAbsent(recording.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            if (isUpToDate(target, parts)) return Optional.of(target);

            long started = System.currentTimeMillis();
            FfmpegService.TranscodeResult result = ffmpeg.concatMp3(parts, target);
            if (!result.success()) {
                log.error("Zusammenfuegen der Tonspur von Aufnahme {} fehlgeschlagen: {}",
                        recording.getId(), result.error());
                return Optional.empty();
            }
            log.info("Tonspur von Aufnahme {} aus {} Segment(en) zusammengefuegt ({} ms)",
                    recording.getId(), parts.size(), System.currentTimeMillis() - started);
            return Optional.of(target);
        } finally {
            lock.unlock();
            locks.remove(recording.getId(), lock);
        }
    }

    /** Hat die Aufnahme ueberhaupt abspielbares Audio? (ohne etwas zu erzeugen) */
    public boolean hasAudio(Recording recording) {
        return !audioParts(recording.getId()).isEmpty();
    }

    /** Fertige MP3-Segmente in Reihenfolge, nur vorhandene Dateien. */
    private List<Path> audioParts(UUID recordingId) {
        return segmentRepo.findByRecordingIdOrderBySeq(recordingId).stream()
                .filter(s -> s.getStatus() == RecordingSegment.Status.READY)
                .map(RecordingSegment::getMp3Path)
                .filter(p -> p != null && !p.isBlank())
                .map(Path::of)
                .filter(Files::exists)
                .toList();
    }

    /**
     * Die zusammengefuegte Datei taugt nur, wenn sie juenger ist als jedes
     * Segment - sonst fehlt ihr ein spaeter hinzugekommenes oder neu
     * transkodiertes Stueck.
     */
    private boolean isUpToDate(Path target, List<Path> parts) {
        try {
            if (!Files.exists(target) || Files.size(target) == 0) return false;
            long built = Files.getLastModifiedTime(target).toMillis();
            for (Path part : parts) {
                if (Files.getLastModifiedTime(part).toMillis() > built) return false;
            }
            return true;
        } catch (IOException e) {
            log.debug("Zustand von {} nicht lesbar, wird neu erzeugt: {}", target, e.getMessage());
            return false;
        }
    }
}
