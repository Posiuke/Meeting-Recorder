package bbbbot.recording;

import bbbbot.domain.Recording;
import bbbbot.domain.RecordingTag;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingTagRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordingTagServiceTest {

    private RecordingTagRepo tagRepo;
    private RecordingRepo recordingRepo;
    private RecordingTagService service;

    private final UUID recordingId = UUID.randomUUID();
    private final List<RecordingTag> stored = new ArrayList<>();

    @BeforeEach
    void setup() {
        tagRepo = mock(RecordingTagRepo.class);
        recordingRepo = mock(RecordingRepo.class);
        service = new RecordingTagService(tagRepo, recordingRepo);

        when(tagRepo.save(any(RecordingTag.class))).thenAnswer(inv -> {
            RecordingTag tag = inv.getArgument(0);
            stored.add(tag);
            return tag;
        });
        when(tagRepo.findByRecordingIdOrderByNameKeyAsc(recordingId)).thenReturn(stored);
        when(tagRepo.findByRecordingIdAndNameKey(any(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            return stored.stream().filter(t -> t.getNameKey().equals(key)).findFirst();
        });
        when(tagRepo.countByRecordingId(recordingId)).thenAnswer(inv -> (long) stored.size());
    }

    @Test
    void legtSchlagwortAnUndVereinheitlichtLeerzeichen() {
        List<String> tags = service.addTag(recordingId, "  Projekt   Nord  ");

        assertThat(tags).containsExactly("Projekt Nord");
        assertThat(stored).singleElement()
                .satisfies(t -> assertThat(t.getNameKey()).isEqualTo("projekt nord"));
    }

    @Test
    void ignoriertDoppeltesSchlagwortUnabhaengigVonGrossschreibung() {
        service.addTag(recordingId, "Protokoll");
        List<String> tags = service.addTag(recordingId, "protokoll");

        assertThat(tags).containsExactly("Protokoll");
        assertThat(stored).hasSize(1);
    }

    @Test
    void lehntLeeresSchlagwortAb() {
        assertThatThrownBy(() -> service.addTag(recordingId, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht leer");
        verify(tagRepo, never()).save(any());
    }

    @Test
    void lehntZuLangesSchlagwortAb() {
        assertThatThrownBy(() -> service.addTag(recordingId, "x".repeat(RecordingTag.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zu lang");
    }

    @Test
    void lehntZuVieleSchlagworteAb() {
        for (int i = 0; i < RecordingTagService.MAX_TAGS_PER_RECORDING; i++) {
            service.addTag(recordingId, "tag-" + i);
        }

        assertThatThrownBy(() -> service.addTag(recordingId, "eins-zu-viel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schlagworte je Aufnahme");
        assertThat(stored).hasSize(RecordingTagService.MAX_TAGS_PER_RECORDING);
    }

    @Test
    void entferntSchlagwortUnabhaengigVonGrossschreibung() {
        service.addTag(recordingId, "Projekt Nord");
        RecordingTag tag = stored.get(0);

        service.removeTag(recordingId, "  projekt nord ");

        verify(tagRepo).delete(tag);
    }

    @Test
    void entfernenEinesUnbekanntenSchlagwortsIstKeinFehler() {
        assertThat(service.removeTag(recordingId, "gibt-es-nicht")).isEmpty();
        verify(tagRepo, never()).delete(any());
    }

    @Test
    void zaehltSichtbareSchlagworteUndSortiertNachHaeufigkeit() {
        Recording first = accessible();
        Recording second = accessible();
        UUID a = first.getId();
        UUID b = second.getId();
        when(recordingRepo.findAllAccessibleBy(any())).thenReturn(List.of(first, second));
        when(tagRepo.findByRecordingIdIn(anyList())).thenReturn(List.of(
                RecordingTag.create(a, "Protokoll"),
                // andere Schreibweise derselben Sache - muss zusammenfallen
                RecordingTag.create(b, "protokoll"),
                RecordingTag.create(a, "Projekt Nord")));

        List<RecordingTagService.TagCount> tags = service.visibleTags(UUID.randomUUID());

        assertThat(tags).hasSize(2);
        assertThat(tags.get(0).name()).isEqualTo("Protokoll");
        assertThat(tags.get(0).count()).isEqualTo(2);
        assertThat(tags.get(1).name()).isEqualTo("Projekt Nord");
        assertThat(tags.get(1).count()).isEqualTo(1);
    }

    @Test
    void liefertSchlagworteMehrererAufnahmenInEinerAbfrage() {
        Recording first = accessible();
        UUID a = first.getId();
        when(tagRepo.findByRecordingIdIn(anyList())).thenReturn(List.of(
                RecordingTag.create(a, "Zweitens"),
                RecordingTag.create(a, "Erstens")));

        var tags = service.tagsOf(List.of(first));

        assertThat(tags.get(a)).containsExactly("Erstens", "Zweitens");
        verify(tagRepo).findByRecordingIdIn(anyList());
    }

    private static Recording accessible() {
        return Recording.start(null, UUID.randomUUID(), null, "/tmp/x", false, true, false);
    }
}
