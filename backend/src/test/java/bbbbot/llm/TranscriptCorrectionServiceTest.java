package bbbbot.llm;

import bbbbot.domain.RecordingSegment;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranscriptCorrectionServiceTest {

    private LlmClient llm;
    private SettingsService settings;
    private TranscriptCorrectionService service;

    @BeforeEach
    void setup() {
        llm = mock(LlmClient.class);
        settings = mock(SettingsService.class);
        GlossaryService glossaryService = mock(GlossaryService.class);
        service = new TranscriptCorrectionService(llm, settings, glossaryService);

        when(settings.getBool(SettingsService.CORRECTION_ENABLED)).thenReturn(true);
        when(settings.get(SettingsService.CORRECTION_SYSTEM_PROMPT)).thenReturn("Glaette.");
        when(settings.getInt(SettingsService.CORRECTION_CHUNK_CHARS)).thenReturn(3000);
        when(settings.getInt(SettingsService.CORRECTION_MAX_SENTENCE_CHARS)).thenReturn(500);
        when(settings.getBool(SettingsService.LLM_DISABLE_THINKING)).thenReturn(true);
    }

    private static RecordingSegment segment(String transcript) {
        RecordingSegment s = RecordingSegment.create(UUID.randomUUID(), 0, "/tmp/x.webm");
        s.setStatus(RecordingSegment.Status.READY);
        s.setTranscriptText(transcript);
        return s;
    }

    private void answer(String content) {
        when(llm.chat(anyString(), anyString(), any()))
                .thenReturn(new LlmClient.LlmResult(true, content, null));
    }

    // ------------------------------------------------------------ Strukturerhalt

    @Test
    void erhaeltZeitstempelUndSprecherUnveraendert() {
        RecordingSegment segment = segment("""
                SPEAKER_00:
                [00:05] ähm also wir müssen die rz sache noch klären.
                [00:12] ja genau.
                """);
        answer("""
                1 | Wir müssen die RZ-Sache noch klären.
                2 | Ja, genau.
                """);

        var result = service.correct(segment, "- RZ = Rechenzentrum");

        assertThat(result.success()).isTrue();
        assertThat(result.correctedSentences()).isEqualTo(2);
        assertThat(result.keptSentences()).isZero();
        assertThat(result.text()).isEqualTo("""
                SPEAKER_00:
                [00:05] Wir müssen die RZ-Sache noch klären.
                [00:12] Ja, genau.""");
    }

    @Test
    void gibtDemModellNurDieTextteile() {
        RecordingSegment segment = segment("""
                SPEAKER_01:
                [01:00] erster satz.
                """);
        answer("1 | Erster Satz.");

        service.correct(segment, "");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), userPrompt.capture(), any());
        // Struktur darf das Modell gar nicht zu sehen bekommen
        assertThat(userPrompt.getValue()).contains("1 | erster satz.");
        assertThat(userPrompt.getValue()).doesNotContain("[01:00]");
        assertThat(userPrompt.getValue()).doesNotContain("SPEAKER_01");
    }

    // -------------------------------------------------------------- Satz-Einheit

    @Test
    void glaettetGanzeSaetzeUeberZeilengrenzenHinweg() {
        // Whisper zerlegt Saetze in Zeitstempel-Zeilen; geglaettet wird der Satz
        RecordingSegment segment = segment("""
                [00:01] und dann haben wir
                [00:04] das thema verschoben.
                [00:09] danach war pause.
                """);
        answer("""
                1 | Dann haben wir das Thema verschoben.
                2 | Danach war Pause.
                """);

        var result = service.correct(segment, "");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), userPrompt.capture(), any());
        assertThat(userPrompt.getValue()).contains("1 | und dann haben wir das thema verschoben.");

        // Der geglaettete Satz traegt den Zeitstempel seiner ERSTEN Zeile,
        // die Folgezeile geht darin auf.
        assertThat(result.text()).isEqualTo("""
                [00:01] Dann haben wir das Thema verschoben.
                [00:09] Danach war Pause.""");
    }

    @Test
    void sprecherwechselBeendetDenSatz() {
        // Ohne diese Grenze wuerden Aussagen zweier Personen zu einem Satz verschmelzen
        List<TranscriptCorrectionService.Unit> units = TranscriptCorrectionService.groupIntoSentences(
                TranscriptCorrectionService.split("""
                        SPEAKER_00:
                        [00:01] ich denke wir sollten
                        SPEAKER_01:
                        [00:03] moment bitte
                        """), 500);

        assertThat(units).hasSize(2);
        assertThat(units.get(0).text()).isEqualTo("ich denke wir sollten");
        assertThat(units.get(1).text()).isEqualTo("moment bitte");
    }

    @Test
    void trenntOhneSatzzeichenNachDerLaengenkappe() {
        // Whisper liefert manchmal gar keine Satzzeichen - dann greift die Notbremse
        String line = "wort ".repeat(20).strip(); // 99 Zeichen
        List<TranscriptCorrectionService.Unit> units = TranscriptCorrectionService.groupIntoSentences(
                TranscriptCorrectionService.split(
                        "[00:01] " + line + "\n[00:05] " + line + "\n[00:09] " + line + "\n"), 150);

        // Nach je zwei Zeilen ist die Kappe von 150 Zeichen ueberschritten
        assertThat(units).hasSize(2);
        assertThat(units.get(0).lineIndexes()).containsExactly(0, 1);
        assertThat(units.get(1).lineIndexes()).containsExactly(2);
    }

    @Test
    void zerschneidetSaetzeNichtUeberSchritte() {
        List<TranscriptCorrectionService.Unit> units = List.of(
                new TranscriptCorrectionService.Unit(List.of(0), "a".repeat(100)),
                new TranscriptCorrectionService.Unit(List.of(1), "b".repeat(100)),
                new TranscriptCorrectionService.Unit(List.of(2), "c".repeat(100)));

        List<List<TranscriptCorrectionService.Unit>> chunks =
                TranscriptCorrectionService.chunk(units, 250);

        // 108 + 108 = 216 passt, ein dritter Satz (324) nicht mehr
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(2);
        assertThat(chunks.get(1)).hasSize(1);
        // Kein Satz taucht zerstueckelt oder doppelt auf
        assertThat(chunks.stream().flatMap(List::stream).toList()).hasSize(3);
    }

    @Test
    void einUeberlangerSatzBleibtEinSchritt() {
        List<TranscriptCorrectionService.Unit> units = List.of(
                new TranscriptCorrectionService.Unit(List.of(0), "a".repeat(900)));

        assertThat(TranscriptCorrectionService.chunk(units, 300)).hasSize(1);
    }

    @Test
    void teiltLangesTranskriptInMehrereSchritte() {
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            transcript.append("[00:").append(String.format("%02d", i)).append("] ")
                    .append("satz ".repeat(40).strip()).append(".\n");
        }
        when(settings.getInt(SettingsService.CORRECTION_CHUNK_CHARS)).thenReturn(600);
        answer("1 | Korrigierter Satz.");

        var result = service.correct(segment(transcript.toString()), "");

        // 10 Saetze zu je ~207 Zeichen bei 600 Zeichen pro Schritt -> 2 Saetze pro Schritt
        assertThat(result.steps()).isEqualTo(5);
        verify(llm, times(5)).chat(anyString(), anyString(), any());
    }

    // ------------------------------------------------------------- Rueckfaelle

    @Test
    void fehlenderSatzBleibtImOriginal() {
        RecordingSegment segment = segment("""
                [00:01] erster satz.
                [00:02] zweiter satz.
                """);
        // Das Modell liefert nur den ersten Satz zurueck
        answer("1 | Erster Satz.");

        var result = service.correct(segment, "");

        assertThat(result.success()).isTrue();
        assertThat(result.correctedSentences()).isEqualTo(1);
        assertThat(result.keptSentences()).isEqualTo(1);
        assertThat(result.text()).isEqualTo("""
                [00:01] Erster Satz.
                [00:02] zweiter satz.""");
    }

    @Test
    void mehrzeiligerSatzBleibtVollstaendigWennDieAntwortFehlt() {
        RecordingSegment segment = segment("""
                [00:01] und dann haben wir
                [00:04] das thema verschoben.
                [00:09] danach war pause.
                """);
        // Nur der zweite Satz kommt zurueck - der erste (zwei Zeilen) bleibt komplett
        answer("2 | Danach war Pause.");

        var result = service.correct(segment, "");

        assertThat(result.text()).isEqualTo("""
                [00:01] und dann haben wir
                [00:04] das thema verschoben.
                [00:09] Danach war Pause.""");
    }

    @Test
    void verwirftAusuferndeAntwort() {
        RecordingSegment segment = segment("[00:01] kurz.");
        answer("1 | " + "Das ist eine viel zu lange Erklaerung des Modells. ".repeat(5));

        var result = service.correct(segment, "");

        // Nichts brauchbar korrigiert -> Original bleibt die einzige Fassung
        assertThat(result.success()).isFalse();
    }

    @Test
    void unbrauchbareAntwortLaesstOriginalStehen() {
        RecordingSegment segment = segment("[00:01] erster satz.");
        answer("Gerne! Hier ist deine Korrektur: Erster Satz.");

        var result = service.correct(segment, "");

        assertThat(result.success()).isFalse();
        assertThat(result.text()).isNull();
    }

    @Test
    void fehlerDesModellsIstKeinAbbruchDerAuswertung() {
        RecordingSegment segment = segment("[00:01] erster satz.");
        when(llm.chat(anyString(), anyString(), any()))
                .thenReturn(new LlmClient.LlmResult(false, null, "LLM nicht erreichbar"));

        var result = service.correct(segment, "");

        // Kein Abbruch der Auswertung: Das Original bleibt die Fassung, und der
        // Grund wird durchgereicht statt hinter einer Sammelmeldung zu verschwinden.
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("LLM nicht erreichbar");
        assertThat(result.llmUnavailable()).isTrue();
    }

    @Test
    void unbrauchbareAntwortStopptDieWeiterenBloeckeNicht() {
        // Zwei Bloecke: Der erste liefert Muell, der zweite eine gute Antwort.
        RecordingSegment segment = segment(manyLines(2));
        when(settings.getInt(SettingsService.CORRECTION_CHUNK_CHARS)).thenReturn(600);
        when(llm.chat(anyString(), anyString(), any()))
                .thenReturn(new LlmClient.LlmResult(true, "Gerne! Hier ist die Korrektur.", null))
                .thenReturn(new LlmClient.LlmResult(true, "1 | Zweiter Block.", null));

        var result = service.correct(segment, "");

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).isEqualTo(result.plannedSteps());
        verify(llm, times(2)).chat(anyString(), anyString(), any());
    }

    @Test
    void brichtNachErstemLlmFehlerAbStattJedenBlockZuVersuchen() {
        // Mehrere Bloecke, das Modell antwortet nie. Jeder Versuch kostet in der
        // Praxis das LLM-Timeout (Standard 300 s) - deshalb darf nur EINER laufen.
        RecordingSegment segment = segment(manyLines(6));
        when(settings.getInt(SettingsService.CORRECTION_CHUNK_CHARS)).thenReturn(600);
        when(llm.chat(anyString(), anyString(), any()))
                .thenReturn(new LlmClient.LlmResult(false, null, "request timed out"));

        var result = service.correct(segment, "");

        verify(llm, times(1)).chat(anyString(), anyString(), any());
        assertThat(result.success()).isFalse();
        assertThat(result.llmUnavailable()).isTrue();
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.plannedSteps()).isGreaterThan(1);
        // Alle Saetze - auch die der nicht mehr versuchten Bloecke - bleiben im Original
        assertThat(result.keptSentences()).isEqualTo(6);
    }

    @Test
    void tokenBudgetRichtetSichNachDerTatsaechlichenBlockgroesse() {
        answer("1 | Erster Satz.");

        service.correct(segment("[00:01] erster satz."), "");

        // Ein kurzer Block braucht kein Budget fuer 3000 Zeichen: Die Antwort ist
        // etwa so lang wie die Anfrage ("1 | erster satz.\n" = 17 Zeichen).
        verify(llm).chat(anyString(), anyString(), eq(17 / 2 + 512));
    }

    @Test
    void gibtNachdenkendenModellenZusaetzlichesBudget() {
        // Darf das Modell "nachdenken", geht das vom selben Budget ab wie die
        // Antwort - ohne Reserve bleibt die Antwort leer (Fall aus dem Betrieb).
        when(settings.getBool(SettingsService.LLM_DISABLE_THINKING)).thenReturn(false);
        when(settings.getInt(SettingsService.LLM_MAX_TOKENS)).thenReturn(2048);
        answer("1 | Erster Satz.");

        service.correct(segment("[00:01] erster satz."), "");

        verify(llm).chat(anyString(), anyString(), eq(17 / 2 + 512 + 2048));
    }

    @Test
    void glossarZaehltNichtInsAntwortBudget() {
        answer("1 | Erster Satz.");
        String glossar = "- RZ = Rechenzentrum\n".repeat(200);

        service.correct(segment("[00:01] erster satz."), glossar);

        // Das Glossar geht in die Frage ein, nicht in die Antwort
        verify(llm).chat(anyString(), anyString(), eq(17 / 2 + 512));
    }

    /** Erzeugt {@code count} Saetze, jeder gut 500 Zeichen lang. */
    private static String manyLines(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("[0").append(i).append(":00] ")
                    .append("wir haben das thema noch einmal ausfuehrlich besprochen ".repeat(9))
                    .append("und so weiter.\n");
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- Zerlegung

    @Test
    void zerlegtStrukturUndText() {
        List<TranscriptCorrectionService.Line> lines = TranscriptCorrectionService.split("""
                SPEAKER_00:
                [00:05] text mit inhalt
                [00:06]
                freie zeile ohne zeitstempel
                """);

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).hasPayload()).isFalse();      // Sprecherzeile = Struktur
        assertThat(lines.get(1).payload).isEqualTo("text mit inhalt");
        assertThat(lines.get(2).hasPayload()).isFalse();      // Zeitstempel ohne Text
        assertThat(lines.get(3).payload).isEqualTo("freie zeile ohne zeitstempel");
    }

    @Test
    void erkenntSatzendenMitAnfuehrungUndFragezeichen() {
        List<TranscriptCorrectionService.Unit> units = TranscriptCorrectionService.groupIntoSentences(
                TranscriptCorrectionService.split("""
                        [00:01] ist das so?
                        [00:02] er sagte "ja."
                        [00:03] danach
                        [00:04] kam nichts mehr!
                        """), 500);

        assertThat(units).hasSize(3);
        assertThat(units.get(2).text()).isEqualTo("danach kam nichts mehr!");
    }

    @Test
    void liestNurZeilenImAntwortformat() {
        Map<Integer, String> parsed = TranscriptCorrectionService.parseAnswer("""
                Hier bitte:
                1 | Erster Satz.
                zwischendrin Geschwaetz
                2 | Zweiter Satz.
                """);

        assertThat(parsed).containsExactlyInAnyOrderEntriesOf(
                Map.of(1, "Erster Satz.", 2, "Zweiter Satz."));
    }

    @Test
    void pruefungEinzelnerSaetze() {
        assertThat(TranscriptCorrectionService.acceptable("kurzer text", "Kurzer Text.")).isTrue();
        assertThat(TranscriptCorrectionService.acceptable("kurzer text", "  ")).isFalse();
        assertThat(TranscriptCorrectionService.acceptable("kurzer text", null)).isFalse();
        assertThat(TranscriptCorrectionService.acceptable("kurz", "a".repeat(200))).isFalse();
    }
}
