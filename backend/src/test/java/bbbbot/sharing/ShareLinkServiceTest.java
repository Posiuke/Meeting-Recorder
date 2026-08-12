package bbbbot.sharing;

import bbbbot.domain.ShareLink;
import bbbbot.repository.Repositories.ShareLinkRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareLinkServiceTest {

    private ShareLinkRepo repo;
    private SettingsService settings;
    private ShareLinkService service;
    private final Map<String, ShareLink> stored = new HashMap<>();

    private final UUID recordingId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        repo = mock(ShareLinkRepo.class);
        settings = mock(SettingsService.class);
        when(settings.getBool(SettingsService.SHARING_PUBLIC_LINKS)).thenReturn(true);
        service = new ShareLinkService(repo, settings);
        stored.clear();
        // Gespeicherte Links merken, damit resolve() sie wiederfindet
        when(repo.save(any(ShareLink.class))).thenAnswer(inv -> {
            ShareLink link = inv.getArgument(0);
            stored.put(link.getToken(), link);
            return link;
        });
        when(repo.findByToken(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0, String.class))));
    }

    @Test
    void tokenIstLangUndZufaellig() {
        String first = service.create(recordingId, ownerId, null, false).getToken();
        String second = service.create(recordingId, ownerId, null, false).getToken();
        assertThat(first).hasSize(ShareLink.TOKEN_LENGTH).isNotEqualTo(second);
        // base64url: keine Zeichen, die in einer URL gequotet werden muessten
        assertThat(first).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void ohneLaufzeitGiltDerLinkBisZumWiderruf() {
        ShareLink link = service.create(recordingId, ownerId, null, false);
        assertThat(link.getExpiresAt()).isNull();
        assertThat(link.isExpired(Instant.now().plus(3650, ChronoUnit.DAYS))).isFalse();
        assertThat(service.resolve(link.getToken())).contains(link);
    }

    @Test
    void laufzeitWirdInTagenGerechnet() {
        ShareLink link = service.create(recordingId, ownerId, 7, false);
        assertThat(link.getExpiresAt())
                .isAfter(Instant.now().plus(6, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(8, ChronoUnit.DAYS));
    }

    @Test
    void abgelaufeneLinksGeltenAlsUnbekannt() {
        ShareLink link = ShareLink.create(recordingId, "abgelaufen", ownerId,
                Instant.now().minusSeconds(1), false);
        stored.put(link.getToken(), link);
        assertThat(service.resolve("abgelaufen")).isEmpty();
    }

    @Test
    void unbekanntesUndLeeresTokenLiefernNichts() {
        assertThat(service.resolve("gibtesnicht")).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
    }

    @Test
    void zugriffZaehltUndWirdNichtBeiJedemAufrufGeschrieben() {
        ShareLink link = service.create(recordingId, ownerId, null, false);

        service.recordView(link);
        assertThat(link.getViews()).isEqualTo(1);
        assertThat(link.getLastViewedAt()).isNotNull();

        // Zweiter Aufruf unmittelbar danach: kein weiterer Schreibvorgang
        service.recordView(link);
        assertThat(link.getViews()).isEqualTo(1);

        // Nach Ablauf des Zeitfensters wird wieder gezaehlt
        link.setLastViewedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        service.recordView(link);
        assertThat(link.getViews()).isEqualTo(2);
    }

    @Test
    void linkEinerAnderenAufnahmeWirdNichtGefunden() {
        ShareLink link = service.create(recordingId, ownerId, null, false);
        when(repo.findById(link.getId())).thenReturn(Optional.of(link));

        assertThat(service.findOfRecording(recordingId, link.getId())).contains(link);
        assertThat(service.findOfRecording(UUID.randomUUID(), link.getId())).isEmpty();
    }

    @Test
    void offenerLinkVerlangtKeineAnmeldung() {
        ShareLink link = service.create(recordingId, ownerId, null, false);
        assertThat(link.isRequireLogin()).isFalse();
        assertThat(service.requiresLogin(link)).isFalse();
    }

    @Test
    void kontogebundenerLinkVerlangtAnmeldung() {
        ShareLink link = service.create(recordingId, ownerId, null, true);
        assertThat(link.isRequireLogin()).isTrue();
        assertThat(service.requiresLogin(link)).isTrue();
    }

    @Test
    void adminNotbremseMachtAuchAlteOffeneLinksKontogebunden() {
        ShareLink offen = service.create(recordingId, ownerId, null, false);
        when(settings.getBool(SettingsService.SHARING_PUBLIC_LINKS)).thenReturn(false);

        assertThat(service.publicLinksAllowed()).isFalse();
        // Der Link bleibt gueltig - er verlangt jetzt aber eine Anmeldung
        assertThat(service.resolve(offen.getToken())).contains(offen);
        assertThat(service.requiresLogin(offen)).isTrue();
    }

    @Test
    void leeresTokenLoestKeinenDatenbankzugriffAus() {
        service.resolve(null);
        verify(repo, never()).findByToken(any());
    }
}
