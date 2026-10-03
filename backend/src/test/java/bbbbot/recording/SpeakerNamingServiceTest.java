package bbbbot.recording;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerNamingServiceTest {

    private final SpeakerNamingService service = new SpeakerNamingService(null, null, null);
    private final Set<String> labels = Set.of("SPEAKER_00", "SPEAKER_01", "SPEAKER_02");

    @Test
    void liestDieJsonAntwortAuchMitUmgebendemText() {
        var result = service.parseLlmAnswer("""
                Hier ist die Zuordnung:
                ```json
                {"speakers":[
                  {"label":"SPEAKER_00","name":"Anna","confidence":"high","evidence":"[00:03] Ich bin Anna"},
                  {"label":"SPEAKER_01","name":"Tom","confidence":"medium","evidence":"[00:20] Was meinst du, Tom?"},
                  {"label":"SPEAKER_02","name":null,"confidence":"low","evidence":""}]}
                ```""", labels);

        assertThat(result).containsOnlyKeys("SPEAKER_00", "SPEAKER_01");
        assertThat(result.get("SPEAKER_00").confidence()).isEqualTo("HIGH");
        assertThat(result.get("SPEAKER_01").confidence()).isEqualTo("MEDIUM");
        assertThat(result.get("SPEAKER_01").source()).isEqualTo("LLM");
    }

    @Test
    void verwirftUnbekannteLabelsUndDoppelteNamen() {
        var result = service.parseLlmAnswer("""
                {"speakers":[
                  {"label":"SPEAKER_09","name":"Eva","confidence":"high"},
                  {"label":"SPEAKER_00","name":"Anna","confidence":"high"},
                  {"label":"SPEAKER_01","name":"anna","confidence":"high"}]}""", labels);

        assertThat(result).isEmpty();
    }

    @Test
    void keinJsonErgibtKeineVorschlaege() {
        assertThat(service.parseLlmAnswer("Ich weiss es nicht.", labels)).isEmpty();
    }

    @Test
    void liestNamenAusDemTeilnehmerprotokoll() {
        assertThat(SpeakerNamingService.participantNames("""
                Aufnahme: 123
                Teilnehmer zu Beginn:
                  - Anna Mueller
                  - Tom
                + 2026-10-03T10:00:00Z JOINED: Eva
                """)).containsExactly("Anna Mueller", "Tom", "Eva");
    }
}
