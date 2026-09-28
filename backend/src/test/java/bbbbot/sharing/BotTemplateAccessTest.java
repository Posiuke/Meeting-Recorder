package bbbbot.sharing;

import bbbbot.domain.BotTemplateShare;
import bbbbot.domain.ShareGrant;
import bbbbot.repository.Repositories.BotTemplateShareRepo;
import bbbbot.repository.Repositories.ShareGrantRepo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Aufnahmen eines Bots aus einer geteilten Vorlage gehen an deren Empfaenger. */
class BotTemplateAccessTest {

    private final BotTemplateShareRepo templateShareRepo = mock(BotTemplateShareRepo.class);
    private final ShareGrantRepo shareGrantRepo = mock(ShareGrantRepo.class);
    private final BotTemplateAccess access = new BotTemplateAccess(templateShareRepo, shareGrantRepo);

    @Test
    void gibtAufnahmeAnNutzerUndGruppenDerVorlageFrei() {
        UUID templateId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID kollege = UUID.randomUUID();
        UUID gruppe = UUID.randomUUID();
        when(templateShareRepo.findByBotTemplateIdOrderByCreatedAtAsc(templateId)).thenReturn(List.of(
                BotTemplateShare.forUser(templateId, kollege, owner),
                BotTemplateShare.forGroup(templateId, gruppe, owner)));

        access.shareRecording(recordingId, templateId);

        ArgumentCaptor<ShareGrant> grants = ArgumentCaptor.forClass(ShareGrant.class);
        verify(shareGrantRepo, times(2)).save(grants.capture());
        assertThat(grants.getAllValues()).allMatch(g -> g.getRecordingId().equals(recordingId));
        assertThat(grants.getAllValues()).extracting(ShareGrant::getGranteeUserId).containsExactly(kollege, null);
        assertThat(grants.getAllValues()).extracting(ShareGrant::getGranteeGroupId).containsExactly(null, gruppe);
    }

    @Test
    void bestehendeFreigabeWirdNichtVerdoppelt() {
        UUID templateId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();
        UUID kollege = UUID.randomUUID();
        when(templateShareRepo.findByBotTemplateIdOrderByCreatedAtAsc(templateId)).thenReturn(List.of(
                BotTemplateShare.forUser(templateId, kollege, UUID.randomUUID())));
        when(shareGrantRepo.existsByRecordingIdAndGranteeUserId(recordingId, kollege)).thenReturn(true);

        access.shareRecording(recordingId, templateId);

        verify(shareGrantRepo, never()).save(any());
    }

    @Test
    void ohneVorlageKeineFreigabe() {
        access.shareRecording(UUID.randomUUID(), null);

        verify(templateShareRepo, never()).findByBotTemplateIdOrderByCreatedAtAsc(any());
    }
}
