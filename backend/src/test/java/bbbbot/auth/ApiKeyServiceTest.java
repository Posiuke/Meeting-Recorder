package bbbbot.auth;

import bbbbot.domain.ApiKey;
import bbbbot.repository.Repositories.ApiKeyRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private final UUID owner = UUID.randomUUID();
    private final List<ApiKey> stored = new ArrayList<>();
    private ApiKeyService service;

    @BeforeEach
    void setup() {
        ApiKeyRepo repo = mock(ApiKeyRepo.class);
        service = new ApiKeyService(repo);

        when(repo.save(any(ApiKey.class))).thenAnswer(inv -> {
            ApiKey key = inv.getArgument(0);
            if (!stored.contains(key)) stored.add(key);
            return key;
        });
        when(repo.findByTokenHash(anyString())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            return stored.stream().filter(k -> k.getTokenHash().equals(hash)).findFirst();
        });
        when(repo.countByOwnerId(owner)).thenAnswer(inv -> (long) stored.size());
    }

    @Test
    void legtSchluesselAnUndSpeichertNurDenAbdruck() {
        ApiKeyService.NewKey created = service.create(owner, "Auswertungsskript", false, null);

        assertThat(created.token()).startsWith(ApiKey.TOKEN_PREFIX).hasSizeGreaterThan(40);
        // Der Klartext darf nirgends in der gespeicherten Zeile stehen
        assertThat(created.key().getTokenHash())
                .isEqualTo(ApiKeyService.hash(created.token()))
                .isNotEqualTo(created.token())
                .hasSize(64);
        assertThat(created.key().getTokenPrefix())
                .isEqualTo(created.token().substring(0, ApiKey.VISIBLE_PREFIX_LENGTH));
    }

    @Test
    void jederSchluesselIstAnders() {
        String first = service.create(owner, "A", false, null).token();
        String second = service.create(owner, "B", false, null).token();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void erkenntDenEigenenSchluesselWieder() {
        String token = service.create(owner, "Skript", false, null).token();

        assertThat(service.authenticate(token)).isPresent()
                .get().satisfies(k -> assertThat(k.getName()).isEqualTo("Skript"));
    }

    @Test
    void weistUnbekannteUndFremdformatigeTokensAb() {
        service.create(owner, "Skript", false, null);

        assertThat(service.authenticate(ApiKey.TOKEN_PREFIX + "voelligFalsch")).isEmpty();
        assertThat(service.authenticate("eyJhbGciOiJIUzM4NCJ9.abc.def")).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    void abgelaufenerSchluesselGiltNichtMehr() {
        ApiKeyService.NewKey created = service.create(owner, "Alt",
                false, Instant.now().plusSeconds(60));
        assertThat(service.authenticate(created.token())).isPresent();

        // Ein Schluessel mit Ablauf in der Vergangenheit (Grenzfall: genau jetzt)
        ApiKey expired = ApiKey.create(owner, "Abgelaufen", ApiKeyService.hash("bbb_expired"),
                "bbb_expired", false, Instant.now().minusSeconds(1));
        stored.add(expired);

        assertThat(expired.isExpired(Instant.now())).isTrue();
        assertThat(service.authenticate("bbb_expired")).isEmpty();
    }

    @Test
    void schreibtLetzteNutzungFortAberNichtBeiJedemAufruf() {
        String token = service.create(owner, "Skript", false, null).token();

        service.authenticate(token);
        Instant firstUse = stored.get(0).getLastUsedAt();
        assertThat(firstUse).isNotNull();

        service.authenticate(token);
        // Zweiter Aufruf innerhalb der Auflösung: unveraendert, kein Schreibvorgang
        assertThat(stored.get(0).getLastUsedAt()).isEqualTo(firstUse);

        stored.get(0).setLastUsedAt(firstUse.minus(2, ChronoUnit.MINUTES));
        service.authenticate(token);
        assertThat(stored.get(0).getLastUsedAt()).isAfter(firstUse.minus(1, ChronoUnit.MINUTES));
    }

    @Test
    void merktSichNurLesenAmSchluessel() {
        ApiKeyService.NewKey readOnly = service.create(owner, "Nur lesen", true, null);

        assertThat(readOnly.key().isReadOnly()).isTrue();
        assertThat(service.authenticate(readOnly.token())).get()
                .satisfies(k -> assertThat(k.isReadOnly()).isTrue());
    }
}
