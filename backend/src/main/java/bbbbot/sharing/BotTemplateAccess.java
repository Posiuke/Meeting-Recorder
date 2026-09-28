package bbbbot.sharing;

import bbbbot.domain.AppUser;
import bbbbot.domain.BotTemplate;
import bbbbot.domain.BotTemplateShare;
import bbbbot.domain.ShareGrant;
import bbbbot.repository.Repositories.BotTemplateShareRepo;
import bbbbot.repository.Repositories.ShareGrantRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Rechte an geteilten Bot-Vorlagen. Wer eine Vorlage geteilt bekommen hat -
 * direkt oder ueber eine Gruppe -, sieht sie, startet daraus einen Bot und
 * steuert die laufenden Bots der Vorlage. Bearbeiten, Loeschen und Teilen
 * bleibt beim Besitzer.
 *
 * <p>Ein Bot aus der Vorlage laeuft immer im Namen des Besitzers - auch wenn
 * ihn ein Empfaenger startet. Seine Aufnahmen gehoeren deshalb dem Besitzer und
 * werden beim Anlegen an dieselben Empfaenger freigegeben wie die Vorlage.
 */
@Service
public class BotTemplateAccess {

    private static final Logger log = LoggerFactory.getLogger(BotTemplateAccess.class);

    private final BotTemplateShareRepo templateShareRepo;
    private final ShareGrantRepo shareGrantRepo;

    public BotTemplateAccess(BotTemplateShareRepo templateShareRepo, ShareGrantRepo shareGrantRepo) {
        this.templateShareRepo = templateShareRepo;
        this.shareGrantRepo = shareGrantRepo;
    }

    /** Ids der Vorlagen, die mit dem Nutzer geteilt sind (ohne die eigenen). */
    public Set<UUID> sharedTemplateIds(AppUser user) {
        return new HashSet<>(templateShareRepo.findTemplateIdsSharedWith(user.getId()));
    }

    /** Besitzer oder Empfaenger einer Freigabe. */
    public boolean canUse(BotTemplate template, AppUser user) {
        return template.getOwnerId().equals(user.getId())
                || sharedTemplateIds(user).contains(template.getId());
    }

    /**
     * Gibt eine neue Aufnahme an alle Empfaenger der Vorlage frei, aus der ihr
     * Bot stammt. Der Stand der Freigaben beim Aufnahmestart zaehlt; spaetere
     * Aenderungen an der Vorlage wirken nur auf neue Aufnahmen.
     */
    public void shareRecording(UUID recordingId, UUID templateId) {
        if (templateId == null) return;
        int count = 0;
        for (BotTemplateShare share : templateShareRepo.findByBotTemplateIdOrderByCreatedAtAsc(templateId)) {
            if (share.getGranteeUserId() != null) {
                if (shareGrantRepo.existsByRecordingIdAndGranteeUserId(recordingId, share.getGranteeUserId())) continue;
                shareGrantRepo.save(ShareGrant.forUser(recordingId, share.getGranteeUserId(), share.getCreatedBy()));
            } else {
                if (shareGrantRepo.existsByRecordingIdAndGranteeGroupId(recordingId, share.getGranteeGroupId())) continue;
                shareGrantRepo.save(ShareGrant.forGroup(recordingId, share.getGranteeGroupId(), share.getCreatedBy()));
            }
            count++;
        }
        if (count > 0) {
            log.info("Aufnahme {}: an {} Empfaenger der Bot-Vorlage {} freigegeben", recordingId, count, templateId);
        }
    }
}
