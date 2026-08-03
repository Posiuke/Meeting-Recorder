package bbbbot.recording;

import bbbbot.bot.BotManager;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Setzt die im Admin-Bereich konfigurierte Aufbewahrungsrichtlinie durch
 * (cleanup.enabled / cleanup.olderThanDays): loescht regelmaessig Aufnahmen,
 * die aelter als die Frist sind, samt Verzeichnis und - per DB-Cascade -
 * Segmenten, Zusammenfassungen, Jobs und Freigaben.
 *
 * Ohne diesen Job wuerden die Einstellungen ins Leere laufen und der
 * Speicher unbegrenzt wachsen.
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    /** Aufnahmen, die noch laufen oder verarbeitet werden, werden nie automatisch geloescht. */
    private static final Set<Recording.Status> PROTECTED = EnumSet.of(
            Recording.Status.RECORDING, Recording.Status.FINALIZING, Recording.Status.PROCESSING);

    private final RecordingRepo recordingRepo;
    private final SettingsService settings;
    private final BotManager botManager;

    public RetentionCleanupService(RecordingRepo recordingRepo, SettingsService settings, BotManager botManager) {
        this.recordingRepo = recordingRepo;
        this.settings = settings;
        this.botManager = botManager;
    }

    /** Stuendliche Pruefung (leichtgewichtig, wenn nichts zu tun ist). */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void cleanup() {
        if (!settings.getBool(SettingsService.CLEANUP_ENABLED)) return;
        long days = settings.getLong(SettingsService.CLEANUP_OLDER_THAN_DAYS);
        if (days <= 0) return;

        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        List<Recording> old = recordingRepo.findByStartedAtBefore(cutoff);
        int deleted = 0;
        for (Recording r : old) {
            if (PROTECTED.contains(r.getStatus())) continue;
            if (botManager.isRecordingActive(r.getId())) continue;
            deleteDirectoryQuietly(Path.of(r.getDirectory()));
            recordingRepo.delete(r);
            deleted++;
        }
        if (deleted > 0) {
            log.info("Aufbewahrungs-Cleanup: {} Aufnahme(n) aelter als {} Tage geloescht", deleted, days);
        }
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Verzeichnis der abgelaufenen Aufnahme konnte nicht geloescht werden: {}", e.getMessage());
        }
    }
}
