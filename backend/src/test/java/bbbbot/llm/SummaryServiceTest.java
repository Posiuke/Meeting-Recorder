package bbbbot.llm;

import bbbbot.domain.Recording;
import bbbbot.domain.Summary;
import bbbbot.recording.ParticipantService;
import bbbbot.repository.Repositories.SummaryRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fassungen der Zusammenfassung: Genau eine ist die aktuelle, sie steht in
 * summary.md, und beim Loeschen der aktuellen uebernimmt die neueste
 * verbliebene. Ueberschrieben wird nie etwas.
 */
class SummaryServiceTest {

    @TempDir
    Path dir;

    private final List<Summary> stored = new ArrayList<>();
    private SummaryService service;
    private Recording recording;

    @BeforeEach
    void setUp() {
        SummaryRepo repo = mock(SummaryRepo.class);
        service = new SummaryService(mock(LlmClient.class), mock(SettingsService.class), repo,
                mock(ParticipantService.class));
        recording = Recording.start(null, UUID.randomUUID(), null, dir.toString(), false, true, false);

        when(repo.save(any(Summary.class))).thenAnswer(inv -> {
            Summary s = inv.getArgument(0);
            if (!stored.contains(s)) stored.add(s);
            return s;
        });
        when(repo.saveAndFlush(any(Summary.class))).thenAnswer(inv -> {
            Summary s = inv.getArgument(0);
            if (!stored.contains(s)) stored.add(s);
            return s;
        });
        when(repo.findByRecordingIdAndCurrentIsTrue(recording.getId()))
                .thenAnswer(inv -> stored.stream().filter(Summary::isCurrent).findFirst());
        when(repo.findByRecordingIdOrderByCreatedAtDesc(recording.getId()))
                .thenAnswer(inv -> stored.stream()
                        .sorted(Comparator.comparing(Summary::getCreatedAt).reversed())
                        .toList());
    }

    /** Fertige Fassung mit Inhalt; die Zeitpunkte trennen "neuer" von "aelter". */
    private Summary done(String markdown, int minutesAgo) {
        Summary s = Summary.create(recording.getId());
        s.setStatus(Summary.Status.DONE);
        s.setMarkdown(markdown);
        s.setFinishedAt(Instant.now());
        setCreatedAt(s, Instant.now().minusSeconds(60L * minutesAgo));
        stored.add(s);
        return s;
    }

    /** {@code createdAt} setzt die Entitaet selbst - fuer die Reihenfolge im Test noetig. */
    private static void setCreatedAt(Summary summary, Instant when) {
        try {
            var field = Summary.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(summary, when);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private String summaryFile() throws Exception {
        Path file = dir.resolve("summary.md");
        return Files.exists(file) ? Files.readString(file) : null;
    }

    @Test
    void neueFassungWirdDieAktuelleUndDieAlteBleibtStehen() throws Exception {
        Summary alt = done("# Alte Fassung\n", 30);
        service.makeCurrent(recording, alt);
        Summary neu = done("# Neue Fassung\n", 0);

        service.makeCurrent(recording, neu);

        assertThat(neu.isCurrent()).isTrue();
        assertThat(alt.isCurrent()).isFalse();
        // Nichts geloescht - die alte Fassung ist weiter da
        assertThat(stored).containsExactly(alt, neu);
        assertThat(summaryFile()).isEqualTo("# Neue Fassung\n");
    }

    @Test
    void aeltereFassungLaesstSichWiederNachVornHolen() throws Exception {
        Summary alt = done("# Alte Fassung\n", 30);
        Summary neu = done("# Neue Fassung\n", 0);
        service.makeCurrent(recording, neu);

        service.makeCurrent(recording, alt);

        assertThat(alt.isCurrent()).isTrue();
        assertThat(neu.isCurrent()).isFalse();
        assertThat(summaryFile()).isEqualTo("# Alte Fassung\n");
        assertThat(service.current(recording.getId())).contains(alt);
    }

    @Test
    void nurEineFertigeFassungMitInhaltKannDieAktuelleSein() {
        Summary gescheitert = Summary.create(recording.getId());
        gescheitert.setStatus(Summary.Status.FAILED);
        gescheitert.setError("LLM nicht erreichbar");
        stored.add(gescheitert);

        assertThatThrownBy(() -> service.makeCurrent(recording, gescheitert))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nachLoeschenDerAktuellenUebernimmtDieNeuesteVerbliebene() throws Exception {
        Summary alt = done("# Alte Fassung\n", 30);
        Summary mittel = done("# Mittlere Fassung\n", 15);
        Summary neu = done("# Neue Fassung\n", 0);
        service.makeCurrent(recording, neu);

        stored.remove(neu);
        service.syncCurrent(recording);

        assertThat(mittel.isCurrent()).isTrue();
        assertThat(alt.isCurrent()).isFalse();
        assertThat(summaryFile()).isEqualTo("# Mittlere Fassung\n");
    }

    @Test
    void ohneVerbliebeneFassungVerschwindetDieDatei() throws Exception {
        Summary einzige = done("# Einzige Fassung\n", 0);
        service.makeCurrent(recording, einzige);
        assertThat(summaryFile()).isNotNull();

        stored.remove(einzige);
        service.syncCurrent(recording);

        assertThat(summaryFile()).isNull();
    }

    @Test
    void bearbeiteteAktuelleFassungLandetInDerDatei() throws Exception {
        Summary aktuell = done("# Vom Modell\n", 0);
        service.makeCurrent(recording, aktuell);

        aktuell.setMarkdown("# Von Hand ueberarbeitet\n");
        aktuell.setEditedAt(Instant.now());
        service.syncCurrent(recording);

        assertThat(summaryFile()).isEqualTo("# Von Hand ueberarbeitet\n");
    }

    /** Eine gescheiterte Fassung darf die aktuelle nicht verdraengen. */
    @Test
    void gescheiterteFassungWirdBeimNachziehenNichtAktuell() throws Exception {
        Summary gescheitert = Summary.create(recording.getId());
        gescheitert.setStatus(Summary.Status.FAILED);
        setCreatedAt(gescheitert, Instant.now());
        stored.add(gescheitert);
        Summary fertig = done("# Fertige Fassung\n", 10);

        service.syncCurrent(recording);

        assertThat(fertig.isCurrent()).isTrue();
        assertThat(gescheitert.isCurrent()).isFalse();
        assertThat(summaryFile()).isEqualTo("# Fertige Fassung\n");
    }
}
