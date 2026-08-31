package bbbbot.processing;

import bbbbot.domain.ProcessingJob;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Kurzform der Aufgabe eines Auftrags. Sie beschriftet die Zeile in der
 * Admin-Uebersicht - drei Schalter zu deuten ist dort einer zu viel.
 */
class ProcessingJobModeTest {

    private static ProcessingJob job() {
        return ProcessingJob.create(UUID.randomUUID(), false);
    }

    @Test
    void ohneSchalterIstEsDieVolleAuswertung() {
        assertThat(job().mode()).isEqualTo("FULL");
    }

    @Test
    void nurTranskription() {
        ProcessingJob job = job();
        job.setTranscribeOnly(true);

        assertThat(job.mode()).isEqualTo("TRANSCRIBE_ONLY");
    }

    @Test
    void erneuteAuswertungEinerFertigenAufnahme() {
        ProcessingJob job = job();
        job.setHadSummary(true);

        assertThat(job.mode()).isEqualTo("REPROCESS");
    }

    /**
     * Die erneute Transkription geht vor: Sie ist der teure Teil (Whisper laeuft
     * fuer alle Segmente neu) und beschreibt den Auftrag treffender als der
     * Umstand, dass schon eine Zusammenfassung existierte.
     */
    @Test
    void erneuteTranskriptionGehtVor() {
        ProcessingJob job = job();
        job.setRedoTranscripts(true);
        job.setHadSummary(true);

        assertThat(job.mode()).isEqualTo("RETRANSCRIBE");
    }

    @Test
    void erneuteTranskriptionOhneAuswertung() {
        ProcessingJob job = job();
        job.setRedoTranscripts(true);
        job.setTranscribeOnly(true);

        assertThat(job.mode()).isEqualTo("RETRANSCRIBE_ONLY");
    }

    @Test
    void gesamtdauerErstNachDemAbschluss() {
        ProcessingJob job = job();
        assertThat(job.durationMs()).isNull();

        Instant start = Instant.parse("2026-08-31T02:00:00Z");
        job.setStartedAt(start);
        // Gestartet, aber nicht fertig: noch keine Dauer.
        assertThat(job.durationMs()).isNull();

        job.setFinishedAt(start.plus(Duration.ofMinutes(4)));
        assertThat(job.durationMs()).isEqualTo(Duration.ofMinutes(4).toMillis());
    }
}
