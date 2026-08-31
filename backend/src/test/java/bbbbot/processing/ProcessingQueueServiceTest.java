package bbbbot.processing;

import bbbbot.domain.ProcessingJob;
import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.ProcessingJobRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Die Betriebssicht auf die Warteschlange: Wartezeiten, die Frage "warum laeuft
 * nichts?", die Dauer-Kennzahlen und die Regeln des erneuten Anstossens.
 */
class ProcessingQueueServiceTest {

    private ProcessingJobRepo jobRepo;
    private RecordingRepo recordingRepo;
    private SettingsService settings;
    private ProcessingQueueService queue;

    private final UUID recordingId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        jobRepo = mock(ProcessingJobRepo.class);
        recordingRepo = mock(RecordingRepo.class);
        settings = mock(SettingsService.class);
        queue = new ProcessingQueueService(jobRepo, recordingRepo, settings);

        // Rund um die Uhr offen, sofern ein Test es nicht anders setzt.
        when(settings.get(SettingsService.PROCESSING_WINDOW_START)).thenReturn("00:00");
        when(settings.get(SettingsService.PROCESSING_WINDOW_END)).thenReturn("00:00");
        when(jobRepo.findByStatusInOrderByCreatedAtAsc(anyList())).thenReturn(List.of());
        when(jobRepo.findTop20ByStatusOrderByFinishedAtDesc(any())).thenReturn(List.of());
        when(jobRepo.findTop50ByStatusOrderByFinishedAtDesc(any())).thenReturn(List.of());
    }

    private ProcessingJob job(ProcessingJob.Status status, boolean immediate) {
        ProcessingJob job = ProcessingJob.create(recordingId, immediate);
        job.setStatus(status);
        return job;
    }

    private Recording recording() {
        Recording r = Recording.start(null, UUID.randomUUID(), null, "/tmp/x", false, true, false);
        r.setTitle("Wochenbesprechung");
        return r;
    }

    // ------------------------------------------------------------ Zeitfenster

    @Test
    void gleicheZeitenBedeutenRundUmDieUhr() {
        assertThat(queue.isWindowOpen()).isTrue();
        assertThat(queue.overview().windowOpen()).isTrue();
    }

    /**
     * Ein unlesbares Zeitfenster darf die Uebersicht nicht mit einem 500er
     * beenden - genau dann will man ja hinsehen. Der Scheduler wuerde daran
     * ebenfalls scheitern, also gilt das Fenster als geschlossen.
     */
    @Test
    void unlesbaresZeitfensterGiltAlsGeschlossen() {
        when(settings.get(SettingsService.PROCESSING_WINDOW_START)).thenReturn("abends");

        assertThat(queue.isWindowOpen()).isFalse();
    }

    @Test
    void meldetDasKonfigurierteFensterMit() {
        when(settings.get(SettingsService.PROCESSING_WINDOW_START)).thenReturn("20:00");
        when(settings.get(SettingsService.PROCESSING_WINDOW_END)).thenReturn("06:00");

        var overview = queue.overview();

        assertThat(overview.windowStart()).isEqualTo("20:00");
        assertThat(overview.windowEnd()).isEqualTo("06:00");
    }

    // ------------------------------------------------------------ Warten

    /**
     * Die haeufigste Rueckfrage lautet "warum laeuft nichts?". Ein wartender,
     * nicht sofortiger Auftrag bei geschlossenem Fenster ist die Antwort.
     */
    @Test
    void erkenntAuftraegeDieAufDasZeitfensterWarten() {
        // Fenster nachts, Test laeuft irgendwann - beide Faelle abdecken, indem
        // das Fenster auf eine Minute in der Vergangenheit gelegt wird.
        java.time.LocalTime now = java.time.LocalTime.now();
        when(settings.get(SettingsService.PROCESSING_WINDOW_START))
                .thenReturn(now.plusMinutes(5).withNano(0).withSecond(0).toString());
        when(settings.get(SettingsService.PROCESSING_WINDOW_END))
                .thenReturn(now.plusMinutes(10).withNano(0).withSecond(0).toString());

        ProcessingJob wartend = job(ProcessingJob.Status.PENDING, false);
        ProcessingJob sofort = job(ProcessingJob.Status.PENDING, true);
        when(jobRepo.findByStatusInOrderByCreatedAtAsc(anyList()))
                .thenReturn(List.of(wartend, sofort));
        when(recordingRepo.findAllById(anyList())).thenReturn(List.of(recording()));

        var overview = queue.overview();

        assertThat(overview.windowOpen()).isFalse();
        assertThat(overview.queue()).hasSize(2);
        assertThat(overview.queue().get(0).waitsForWindow()).isTrue();
        // Ein Sofort-Auftrag wartet nicht auf das Fenster.
        assertThat(overview.queue().get(1).waitsForWindow()).isFalse();
    }

    /**
     * Wartezeit heisst "bis zum Start". Bei einem laufenden Auftrag ist alles
     * danach Laufzeit und gehoert nicht in dieselbe Zahl.
     */
    @Test
    void wartezeitEndetMitDemStart() {
        ProcessingJob laufend = job(ProcessingJob.Status.RUNNING, true);
        laufend.setStartedAt(laufend.getCreatedAt().plus(Duration.ofMinutes(7)));
        when(jobRepo.findByStatusInOrderByCreatedAtAsc(anyList())).thenReturn(List.of(laufend));
        when(recordingRepo.findAllById(anyList())).thenReturn(List.of(recording()));

        var detail = queue.overview().queue().get(0);

        assertThat(detail.waitingMs()).isEqualTo(Duration.ofMinutes(7).toMillis());
    }

    @Test
    void wartezeitEinesWartendenAuftragsWaechst() {
        ProcessingJob wartend = job(ProcessingJob.Status.PENDING, true);
        when(jobRepo.findByStatusInOrderByCreatedAtAsc(anyList())).thenReturn(List.of(wartend));
        when(recordingRepo.findAllById(anyList())).thenReturn(List.of(recording()));

        assertThat(queue.overview().queue().get(0).waitingMs()).isNotNull().isGreaterThanOrEqualTo(0);
    }

    /** Die Aufnahme kann geloescht sein, waehrend der Auftrag noch in der Liste steht. */
    @Test
    void haeltEinenAuftragOhneAufnahmeAus() {
        when(jobRepo.findByStatusInOrderByCreatedAtAsc(anyList()))
                .thenReturn(List.of(job(ProcessingJob.Status.PENDING, true)));
        when(recordingRepo.findAllById(anyList())).thenReturn(List.of());

        var detail = queue.overview().queue().get(0);

        assertThat(detail.recording()).isNull();
        assertThat(detail.job()).isNotNull();
    }

    // ------------------------------------------------------------ Dauern

    /**
     * Median statt Mittelwert: Eine einzelne sehr lange Aufnahme soll das Bild
     * nicht verschieben. Das Maximum steht daneben, weil der Ausreisser
     * interessiert.
     */
    @Test
    void berechnetMedianUndMaximumDerLetztenLaeufe() {
        when(jobRepo.findTop50ByStatusOrderByFinishedAtDesc(ProcessingJob.Status.DONE))
                .thenReturn(List.of(done(60_000, 40_000L, null, 20_000L),
                        done(120_000, 90_000L, 10_000L, 20_000L),
                        done(3_600_000, 3_500_000L, null, 100_000L)));

        var durations = queue.overview().durations();

        assertThat(durations.sample()).isEqualTo(3);
        assertThat(durations.medianMs()).isEqualTo(120_000);
        assertThat(durations.maxMs()).isEqualTo(3_600_000);
        assertThat(durations.medianSttMs()).isEqualTo(90_000);
        // Nur ein Auftrag hatte eine Glaettung - dann ist die genau der Median.
        assertThat(durations.medianCorrectionMs()).isEqualTo(10_000);
        assertThat(durations.medianSummaryMs()).isEqualTo(20_000);
    }

    @Test
    void ohneAbgeschlosseneAuftraegeKeineKennzahlen() {
        var durations = queue.overview().durations();

        assertThat(durations.sample()).isZero();
        assertThat(durations.medianMs()).isNull();
        assertThat(durations.maxMs()).isNull();
        assertThat(durations.medianSttMs()).isNull();
    }

    /** Bei gerader Anzahl liegt der Median zwischen den beiden mittleren Werten. */
    @Test
    void medianBeiGeraderAnzahl() {
        when(jobRepo.findTop50ByStatusOrderByFinishedAtDesc(ProcessingJob.Status.DONE))
                .thenReturn(List.of(done(10_000, null, null, null), done(20_000, null, null, null)));

        assertThat(queue.overview().durations().medianMs()).isEqualTo(15_000);
    }

    // ------------------------------------------------------------ Erneut anstossen

    @Test
    void stoesstEinenGescheitertenAuftragErneutAn() {
        ProcessingJob gescheitert = job(ProcessingJob.Status.FAILED, false);
        gescheitert.setAttempts(3);
        gescheitert.setLastError("Whisper nicht erreichbar");
        gescheitert.setStartedAt(Instant.now());
        gescheitert.setFinishedAt(Instant.now());
        gescheitert.setSttMs(1234L);
        when(jobRepo.findById(gescheitert.getId())).thenReturn(Optional.of(gescheitert));
        when(recordingRepo.findById(recordingId)).thenReturn(Optional.of(recording()));

        ProcessingJob result = queue.retry(gescheitert.getId());

        assertThat(result.getStatus()).isEqualTo(ProcessingJob.Status.PENDING);
        // Ohne Ruecksetzen waere ein Auftrag mit verbrauchten Versuchen dauerhaft
        // blockiert - und genau dafuer gibt es den Knopf.
        assertThat(result.getAttempts()).isZero();
        // Wer morgens drueckt, hat die Ursache behoben und will das Ergebnis heute.
        assertThat(result.isImmediate()).isTrue();
        assertThat(result.getLastError()).isNull();
        assertThat(result.getStartedAt()).isNull();
        assertThat(result.getFinishedAt()).isNull();
        assertThat(result.getSttMs()).isNull();
        verify(jobRepo).save(gescheitert);
    }

    @Test
    void stoesstNurGescheiterteAuftraegeErneutAn() {
        for (ProcessingJob.Status status : List.of(ProcessingJob.Status.PENDING,
                ProcessingJob.Status.RUNNING, ProcessingJob.Status.DONE)) {
            ProcessingJob job = job(status, false);
            when(jobRepo.findById(job.getId())).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> queue.retry(job.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Nur gescheiterte Auftraege");
        }
        verify(jobRepo, never()).save(any());
    }

    @Test
    void unbekannterAuftragLaesstSichNichtAnstossen() {
        UUID unbekannt = UUID.randomUUID();
        when(jobRepo.findById(unbekannt)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queue.retry(unbekannt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht gefunden");
    }

    /** Ohne Aufnahme laeuft der Auftrag sofort wieder ins Leere. */
    @Test
    void ohneAufnahmeKeinNeuerVersuch() {
        ProcessingJob gescheitert = job(ProcessingJob.Status.FAILED, false);
        when(jobRepo.findById(gescheitert.getId())).thenReturn(Optional.of(gescheitert));
        when(recordingRepo.findById(recordingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queue.retry(gescheitert.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existiert nicht mehr");
        verify(jobRepo, never()).save(any());
    }

    /** Abgeschlossener Auftrag mit Schrittdauern. */
    private ProcessingJob done(long totalMs, Long sttMs, Long correctionMs, Long summaryMs) {
        ProcessingJob job = job(ProcessingJob.Status.DONE, false);
        Instant start = Instant.now().minusMillis(totalMs);
        job.setStartedAt(start);
        job.setFinishedAt(start.plusMillis(totalMs));
        job.setSttMs(sttMs);
        job.setCorrectionMs(correctionMs);
        job.setSummaryMs(summaryMs);
        return job;
    }
}
