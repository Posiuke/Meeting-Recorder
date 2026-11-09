package bbbbot.stt.transcribe;

import bbbbot.config.AppProperties;
import bbbbot.settings.SettingsService;
import bbbbot.stt.TranscriptAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.transcribe.model.StartTranscriptionJobRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranscribeParsingTest {

    private static final String JSON = """
            {"results":{"language_code":"de-DE","items":[
              {"type":"pronunciation","start_time":"0.5","end_time":"0.9","speaker_label":"spk_0","alternatives":[{"content":"Hallo"}]},
              {"type":"punctuation","alternatives":[{"content":","}]},
              {"type":"pronunciation","start_time":"1.0","end_time":"1.4","speaker_label":"spk_0","alternatives":[{"content":"Julia"}]},
              {"type":"punctuation","alternatives":[{"content":"."}]},
              {"type":"pronunciation","start_time":"1.6","end_time":"1.9","speaker_label":"spk_1","alternatives":[{"content":"Hallo"}]},
              {"type":"pronunciation","start_time":"2.0","end_time":"2.4","speaker_label":"spk_1","alternatives":[{"content":"zurück"}]},
              {"type":"pronunciation","start_time":"9.0","end_time":"9.5","speaker_label":"spk_1","alternatives":[{"content":"Später"}]},
              {"type":"punctuation","alternatives":[{"content":"?"}]},
              {"type":"pronunciation","start_time":"605.0","end_time":"605.6","speaker_label":"spk_0","alternatives":[{"content":"Ende"}]}
            ]}}""";

    @Test
    void woerterWerdenZuSaetzenJeSprecher() throws Exception {
        List<TranscribeResult.Utterance> u = TranscribeResult.parse(new ObjectMapper().readTree(JSON));
        assertThat(u).extracting(TranscribeResult.Utterance::text)
                .containsExactly("Hallo, Julia.", "Hallo zurück", "Später?", "Ende");
        assertThat(u).extracting(TranscribeResult.Utterance::speaker)
                .containsExactly("SPEAKER_00", "SPEAKER_01", "SPEAKER_01", "SPEAKER_00");
        // lange Pause (2.4 -> 9.0) beginnt eine neue Zeile
        assertThat(u.get(2).start()).isEqualTo(9.0);
        assertThat(TranscribeResult.languageCode(new ObjectMapper().readTree(JSON))).isEqualTo("de-DE");
    }

    @Test
    void aufteilungAufSegmenteMitRelativenZeitenUndStabilenSprechern() throws Exception {
        List<TranscribeResult.Utterance> u = TranscribeResult.parse(new ObjectMapper().readTree(JSON));
        // zwei Segmente: 600,4 s und 30 s
        List<String> texts = SegmentSplitter.split(u, List.of(600_400L, 30_000L));
        assertThat(texts.get(0)).startsWith("SPEAKER_00:\n[00:00] Hallo, Julia.")
                .contains("SPEAKER_01:\n[00:01] Hallo zurück").contains("[00:09] Später?");
        assertThat(texts.get(1)).isEqualTo("SPEAKER_00:\n[00:05] Ende");

        // Zusammengesetzt ergeben sich wieder die absoluten Zeiten - auch fuer
        // das zweite Segment (600 s gerundeter Versatz + 5 s)
        var seg1 = bbbbot.domain.RecordingSegment.create(java.util.UUID.randomUUID(), 0, "a.webm");
        seg1.setDurationMs(600_400L);
        seg1.setTranscriptText(texts.get(0));
        var seg2 = bbbbot.domain.RecordingSegment.create(java.util.UUID.randomUUID(), 1, "b.webm");
        seg2.setDurationMs(30_000L);
        seg2.setTranscriptText(texts.get(1));
        List<TranscriptAssembler.Entry> entries = TranscriptAssembler.assemble(List.of(seg1, seg2));
        assertThat(entries).extracting(TranscriptAssembler.Entry::startSeconds).containsExactly(0L, 1L, 9L, 605L);
        assertThat(entries).extracting(TranscriptAssembler.Entry::speaker)
                .containsExactly("SPEAKER_00", "SPEAKER_01", "SPEAKER_01", "SPEAKER_00");
    }

    @Test
    void ohneSprecherKeineKopfzeilen() {
        List<String> texts = SegmentSplitter.split(List.of(new TranscribeResult.Utterance(3, 4, null, "Test.")),
                List.of(0L));
        assertThat(texts).containsExactly("[00:03] Test.");
    }

    @Test
    void sprachcodes() {
        assertThat(AwsTranscriber.languageCode("de")).isEqualTo("de-DE");
        assertThat(AwsTranscriber.languageCode("en")).isEqualTo("en-US");
        assertThat(AwsTranscriber.languageCode("pt-br")).isEqualTo("pt-BR");
        assertThat(AwsTranscriber.languageCode("xx")).isNull();
        assertThat(AwsTranscriber.vocabularyLanguage("meeting-recorder-de-DE")).isEqualTo("de-DE");
    }

    @Test
    void glossarbegriffeAlsVocabularyPhrasen() {
        assertThat(AwsTranscriber.vocabularyPhrase("WIKAVIS")).isEqualTo("WIKAVIS");
        assertThat(AwsTranscriber.vocabularyPhrase("Saarbrücker Zeitung")).isEqualTo("Saarbrücker-Zeitung");
        assertThat(AwsTranscriber.vocabularyPhrase("z.B.")).isEqualTo("z.B.");
        assertThat(AwsTranscriber.vocabularyPhrase("S3")).as("Ziffern nicht erlaubt").isNull();
        assertThat(AwsTranscriber.vocabularyPhrase("C++")).isNull();
        assertThat(AwsTranscriber.vocabularyPhrase("  ")).isNull();
    }

    @Test
    void auftragMitSpracheSprechernUndVocabulary() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getInt(SettingsService.WHISPER_AWS_MAX_SPEAKERS)).thenReturn(8);
        AwsTranscriber t = new AwsTranscriber(settings, new AppProperties());

        StartTranscriptionJobRequest fixed = t.request("j", "s3://b/k.mp3", "de", true, "meeting-recorder-de-DE");
        assertThat(fixed.languageCodeAsString()).isEqualTo("de-DE");
        assertThat(fixed.settings().showSpeakerLabels()).isTrue();
        assertThat(fixed.settings().maxSpeakerLabels()).isEqualTo(8);
        assertThat(fixed.settings().vocabularyName()).isEqualTo("meeting-recorder-de-DE");

        StartTranscriptionJobRequest auto = t.request("j", "s3://b/k.mp3", "", false, "meeting-recorder-de-DE");
        assertThat(auto.identifyLanguage()).isTrue();
        assertThat(auto.languageOptionsAsStrings()).contains("de-DE", "en-US");
        assertThat(auto.languageIdSettingsAsStrings()).containsKey("de-DE");
        assertThat(auto.settings()).isNull();
    }

    @Test
    void bucketAusEinstellungOderAblage() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.get(SettingsService.WHISPER_AWS_BUCKET)).thenReturn("");
        AppProperties props = new AppProperties();
        AwsTranscriber t = new AwsTranscriber(settings, props);
        assertThat(t.bucket()).isNull();

        props.getStorage().setBackend("s3");
        props.getStorage().getS3().setBucket("recorder-data");
        assertThat(t.bucket()).isEqualTo("recorder-data");

        when(settings.get(SettingsService.WHISPER_AWS_BUCKET)).thenReturn("eigener");
        assertThat(t.bucket()).isEqualTo("eigener");
    }
}
