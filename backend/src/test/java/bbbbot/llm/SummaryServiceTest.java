package bbbbot.llm;

import bbbbot.docs.RecordingDocumentService;
import bbbbot.domain.Recording;
import bbbbot.domain.Summary;
import bbbbot.recording.ParticipantService;
import bbbbot.repository.Repositories.SummaryRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    private LlmClient llm;
    private SettingsService settings;
    private RecordingDocumentService documentService;

    @BeforeEach
    void setUp() {
        SummaryRepo repo = mock(SummaryRepo.class);
        llm = mock(LlmClient.class);
        settings = mock(SettingsService.class);
        documentService = mock(RecordingDocumentService.class);
        when(documentService.promptBlock(any(UUID.class))).thenReturn("");
        service = new SummaryService(llm, settings, repo, mock(ParticipantService.class),
                documentService);
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

    /**
     * Modell und Temperatur der Aufnahme (in der Regel aus der gewaehlten
     * Vorlage) gehen vor den Admin-Vorgaben - und stehen anschliessend an der
     * Fassung, damit zwei Fassungen desselben Transkripts vergleichbar sind.
     */
    @Test
    void modellUndTemperaturDerAufnahmeGehenVorUndStehenAnDerFassung() {
        when(settings.get(SettingsService.SUMMARY_SYSTEM_PROMPT)).thenReturn("Fasse zusammen.");
        when(settings.get(SettingsService.SUMMARY_LANGUAGE)).thenReturn("de");
        when(settings.getInt(SettingsService.SUMMARY_CHUNK_CHARS)).thenReturn(12_000);
        when(settings.get(SettingsService.LLM_MODEL)).thenReturn("standard-modell");
        when(settings.getDouble(SettingsService.LLM_TEMPERATURE)).thenReturn(0.3);
        when(llm.chat(anyString(), anyString(), any(LlmClient.Overrides.class)))
                .thenReturn(new LlmClient.LlmResult(true, "# Ergebnis\n- Punkt", null));
        recording.setSummaryModel("vergleichs-modell");
        recording.setSummaryTemperature(0.9);

        Summary summary = service.summarize(recording, List.of());

        assertThat(summary.getStatus()).isEqualTo(Summary.Status.DONE);
        assertThat(summary.getModel()).isEqualTo("vergleichs-modell");
        assertThat(summary.getTemperature()).isEqualTo(0.9);
        assertThat(summary.getSystemPrompt()).isEqualTo("Fasse zusammen.");
        assertThat(summary.isCurrent()).isTrue();

        ArgumentCaptor<LlmClient.Overrides> overrides =
                ArgumentCaptor.forClass(LlmClient.Overrides.class);
        verify(llm).chat(anyString(), anyString(), overrides.capture());
        assertThat(overrides.getValue().model()).isEqualTo("vergleichs-modell");
        assertThat(overrides.getValue().temperature()).isEqualTo(0.9);
    }

    /** Ohne eigene Angabe an der Aufnahme gilt die Vorgabe des Admins. */
    @Test
    void ohneAngabeAnDerAufnahmeGiltDieAdminVorgabe() {
        when(settings.get(SettingsService.SUMMARY_SYSTEM_PROMPT)).thenReturn("Fasse zusammen.");
        when(settings.get(SettingsService.SUMMARY_LANGUAGE)).thenReturn("de");
        when(settings.getInt(SettingsService.SUMMARY_CHUNK_CHARS)).thenReturn(12_000);
        when(settings.get(SettingsService.LLM_MODEL)).thenReturn("standard-modell");
        when(settings.getDouble(SettingsService.LLM_TEMPERATURE)).thenReturn(0.3);
        when(llm.chat(anyString(), anyString(), any(LlmClient.Overrides.class)))
                .thenReturn(new LlmClient.LlmResult(true, "# Ergebnis", null));

        Summary summary = service.summarize(recording, List.of());

        assertThat(summary.getModel()).isEqualTo("standard-modell");
        assertThat(summary.getTemperature()).isEqualTo(0.3);
    }

    /**
     * Beigefuegte Unterlagen gehen in JEDEN Aufruf ein - auch ins Konsolidieren.
     * Der Kontext wird in Bloecke geschnitten; stuende der Unterlagen-Abschnitt nur
     * im Kontext, kaeme er in den spaeteren Bloecken nie an.
     */
    @Test
    void unterlagenGehenInJedenAuswertungsschrittEin() {
        when(settings.get(SettingsService.SUMMARY_SYSTEM_PROMPT)).thenReturn("Fasse zusammen.");
        when(settings.get(SettingsService.SUMMARY_LANGUAGE)).thenReturn("de");
        // Kleine Bloecke erzwingen mehrere Aufrufe plus einen Merge-Aufruf
        when(settings.getInt(SettingsService.SUMMARY_CHUNK_CHARS)).thenReturn(200);
        when(settings.get(SettingsService.LLM_MODEL)).thenReturn("modell");
        when(settings.getDouble(SettingsService.LLM_TEMPERATURE)).thenReturn(0.3);
        when(documentService.promptBlock(recording.getId()))
                .thenReturn("# Beigefuegte Unterlagen\n## tagesordnung.md\n1. Projekt Nord\n\n");
        when(llm.chat(anyString(), anyString(), any(LlmClient.Overrides.class)))
                .thenReturn(new LlmClient.LlmResult(true, "# Teil", null));
        recording.setParticipantsLog("Teilnehmer trat bei. ".repeat(60));

        service.summarize(recording, List.of());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeast(3)).chat(anyString(), prompts.capture(), any(LlmClient.Overrides.class));
        assertThat(prompts.getAllValues())
                .allSatisfy(prompt -> assertThat(prompt).contains("tagesordnung.md"));
        // Der letzte Aufruf ist das Konsolidieren - auch dort stehen die Unterlagen
        assertThat(prompts.getAllValues().getLast()).contains("Teil-Zusammenfassungen");
    }

    /** Ohne Unterlagen (oder abgeschaltet) entfaellt der Abschnitt ganz. */
    @Test
    void ohneUnterlagenBleibtDerPromptWieVorher() {
        when(settings.get(SettingsService.SUMMARY_SYSTEM_PROMPT)).thenReturn("Fasse zusammen.");
        when(settings.get(SettingsService.SUMMARY_LANGUAGE)).thenReturn("de");
        when(settings.getInt(SettingsService.SUMMARY_CHUNK_CHARS)).thenReturn(12_000);
        when(settings.get(SettingsService.LLM_MODEL)).thenReturn("modell");
        when(settings.getDouble(SettingsService.LLM_TEMPERATURE)).thenReturn(0.3);
        when(llm.chat(anyString(), anyString(), any(LlmClient.Overrides.class)))
                .thenReturn(new LlmClient.LlmResult(true, "# Ergebnis", null));

        service.summarize(recording, List.of());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), prompt.capture(), any(LlmClient.Overrides.class));
        assertThat(prompt.getValue()).doesNotContain("Beigefuegte Unterlagen");
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
