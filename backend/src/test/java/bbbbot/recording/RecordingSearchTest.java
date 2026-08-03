package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.RecordingTagRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingSearchTest {

    private RecordingRepo recordingRepo;
    private RecordingTagRepo tagRepo;
    private RecordingSegmentRepo segmentRepo;
    private SummaryRepo summaryRepo;
    private RecordingSearch search;

    private final UUID user = UUID.randomUUID();

    private Recording besprechung;
    private Recording schulung;

    @BeforeEach
    void setup() {
        recordingRepo = mock(RecordingRepo.class);
        tagRepo = mock(RecordingTagRepo.class);
        segmentRepo = mock(RecordingSegmentRepo.class);
        summaryRepo = mock(SummaryRepo.class);
        search = new RecordingSearch(recordingRepo, tagRepo, segmentRepo, summaryRepo);

        besprechung = recording("Wochenbesprechung Technik", "https://bbb.example/b/abc");
        schulung = recording("Schulung Neulinge", "https://bbb.example/b/xyz");
        when(recordingRepo.findAllAccessibleBy(user)).thenReturn(List.of(besprechung, schulung));
        when(tagRepo.findRecordingIdsByNameKeyLike(anyString())).thenReturn(List.of());
        when(tagRepo.findRecordingIdsByNameKey(anyString())).thenReturn(List.of());
        when(segmentRepo.findRecordingIdsByTranscriptLike(anyString())).thenReturn(List.of());
        when(summaryRepo.findRecordingIdsByMarkdownLike(anyString())).thenReturn(List.of());
    }

    private static Recording recording(String title, String meetingUrl) {
        Recording r = Recording.start(UUID.randomUUID(), UUID.randomUUID(), meetingUrl, "/tmp/x",
                false, true, false);
        r.setTitle(title);
        return r;
    }

    @Test
    void ohneFilterAlleZugaenglichenAufnahmen() {
        assertThat(search.search(user, null, null, false)).containsExactly(besprechung, schulung);
        assertThat(search.search(user, "   ", "", false)).containsExactly(besprechung, schulung);
    }

    @Test
    void findetImTitelUnabhaengigVonGrossschreibung() {
        assertThat(search.search(user, "WOCHEN", null, false)).containsExactly(besprechung);
        assertThat(search.search(user, "neulinge", null, false)).containsExactly(schulung);
    }

    @Test
    void findetInDerMeetingUrl() {
        assertThat(search.search(user, "b/xyz", null, false)).containsExactly(schulung);
    }

    @Test
    void findetUeberSchlagwort() {
        when(tagRepo.findRecordingIdsByNameKeyLike(anyString())).thenReturn(List.of(schulung.getId()));

        assertThat(search.search(user, "projekt", null, false)).containsExactly(schulung);
    }

    @Test
    void durchsuchtInhalteNurAufWunsch() {
        when(segmentRepo.findRecordingIdsByTranscriptLike(anyString()))
                .thenReturn(List.of(besprechung.getId()));

        assertThat(search.search(user, "haushaltsmittel", null, false)).isEmpty();
        verify(segmentRepo, never()).findRecordingIdsByTranscriptLike(anyString());

        assertThat(search.search(user, "haushaltsmittel", null, true)).containsExactly(besprechung);
    }

    @Test
    void findetInDerZusammenfassung() {
        when(summaryRepo.findRecordingIdsByMarkdownLike(anyString())).thenReturn(List.of(schulung.getId()));

        assertThat(search.search(user, "beschluss", null, true)).containsExactly(schulung);
    }

    @Test
    void schlagwortfilterSchraenktEin() {
        when(tagRepo.findRecordingIdsByNameKey("protokoll")).thenReturn(List.of(besprechung.getId()));

        assertThat(search.search(user, null, "Protokoll", false)).containsExactly(besprechung);
        assertThat(search.search(user, null, "unbekannt", false)).isEmpty();
    }

    @Test
    void schlagwortfilterUndSuchbegriffZusammen() {
        when(tagRepo.findRecordingIdsByNameKey("protokoll"))
                .thenReturn(List.of(besprechung.getId(), schulung.getId()));

        assertThat(search.search(user, "schulung", "protokoll", false)).containsExactly(schulung);
    }

    @Test
    void entschaerftPlatzhalterInDerEingabe() {
        // "50%" darf nicht als LIKE-Platzhalter wirken, sondern muss sich selbst suchen
        search.search(user, "50%", null, false);

        verify(tagRepo).findRecordingIdsByNameKeyLike("%50!%%");
    }

    @Test
    void ohneZugaenglicheAufnahmenKeineInhaltsabfragen() {
        when(recordingRepo.findAllAccessibleBy(any())).thenReturn(List.of());

        assertThat(search.search(user, "egal", null, true)).isEmpty();
        verify(segmentRepo, never()).findRecordingIdsByTranscriptLike(anyString());
        verify(summaryRepo, never()).findRecordingIdsByMarkdownLike(anyString());
    }
}
