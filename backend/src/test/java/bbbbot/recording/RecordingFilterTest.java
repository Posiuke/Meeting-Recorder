package bbbbot.recording;

import bbbbot.domain.Recording;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Die Deutung der Filterangaben aus der Anfrage: Was ist gesetzt, was heisst
 * "leer", und was ist so falsch, dass der Aufruf scheitern muss.
 */
class RecordingFilterTest {

    private static RecordingFilter of(String q, String tag, boolean content,
                                      String from, String to, String source, String owner) {
        return RecordingFilter.of(q, tag, content, from, to, source, owner);
    }

    @Test
    void leereAngabenBedeutenKeinFilter() {
        RecordingFilter filter = of("  ", "  ", false, "", null, null, "");

        assertThat(filter.text()).isNull();
        assertThat(filter.tagKey()).isNull();
        assertThat(filter.from()).isNull();
        assertThat(filter.to()).isNull();
        assertThat(filter.source()).isNull();
        assertThat(filter.ownerId()).isNull();
        assertThat(filter.ownerScope()).isEqualTo(RecordingFilter.OwnerScope.ALL);
    }

    @Test
    void suchbegriffWirdKleingeschriebenUndBeschnitten() {
        assertThat(of("  WOCHENbesprechung  ", null, false, null, null, null, null).text())
                .isEqualTo("wochenbesprechung");
    }

    /** Ein sehr langer Begriff wird gekuerzt, statt eine teure Abfrage auszuloesen. */
    @Test
    void kuerztZuLangeSuchbegriffe() {
        String lang = "a".repeat(RecordingSearch.MAX_QUERY_LENGTH + 50);

        assertThat(of(lang, null, false, null, null, null, null).text())
                .hasSize(RecordingSearch.MAX_QUERY_LENGTH);
    }

    @Test
    void schlagwortWirdAufDenVergleichsschluesselGebracht() {
        assertThat(of(null, "  Projekt   Nord ", false, null, null, null, null).tagKey())
                .isEqualTo("projekt nord");
    }

    @Test
    void erkenntZeitpunkteUndDatenAngaben() {
        RecordingFilter filter = of(null, null, false,
                "2026-08-01T06:30:00Z", "2026-08-31T22:00:00Z", null, null);

        assertThat(filter.from()).isEqualTo(Instant.parse("2026-08-01T06:30:00Z"));
        assertThat(filter.to()).isEqualTo(Instant.parse("2026-08-31T22:00:00Z"));
    }

    /**
     * Ein reines Datum meint den ganzen Tag: Die Untergrenze liegt an seinem
     * Beginn, die Obergrenze am Beginn des Folgetags (und ist ausschliessend).
     */
    @Test
    void einDatumMeintDenGanzenTag() {
        RecordingFilter filter = of(null, null, false, "2026-08-01", "2026-08-31", null, null);

        ZoneId zone = ZoneId.systemDefault();
        assertThat(filter.from())
                .isEqualTo(LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant());
        assertThat(filter.to())
                .isEqualTo(LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant());
    }

    @Test
    void weistUnverstaendlicheZeitangabenAb() {
        assertThatThrownBy(() -> of(null, null, false, "gestern", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
        assertThatThrownBy(() -> of(null, null, false, null, "31.08.2026", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to");
    }

    @Test
    void weistEinenZeitraumAbDerVorSeinemBeginnEndet() {
        assertThatThrownBy(() -> of(null, null, false, "2026-08-31", "2026-08-01", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endet vor seinem Beginn");
    }

    /** Gleicher Tag fuer Anfang und Ende ist gueltig - es ist genau dieser Tag. */
    @Test
    void einZelnerTagIstEinGueltigerZeitraum() {
        RecordingFilter filter = of(null, null, false, "2026-08-31", "2026-08-31", null, null);

        assertThat(filter.to()).isAfter(filter.from());
    }

    @Test
    void erkenntDieQuellen() {
        assertThat(of(null, null, false, null, null, "bot", null).source())
                .isEqualTo(Recording.Source.BOT);
        assertThat(of(null, null, false, null, null, "  Upload ", null).source())
                .isEqualTo(Recording.Source.UPLOAD);
        assertThat(of(null, null, false, null, null, "CAPTURE", null).source())
                .isEqualTo(Recording.Source.CAPTURE);
    }

    @Test
    void weistUnbekannteQuellenAb() {
        assertThatThrownBy(() -> of(null, null, false, null, null, "telefon", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unbekannte Quelle");
    }

    @Test
    void erkenntDieBesitzerfilter() {
        assertThat(of(null, null, false, null, null, null, "mine").ownerScope())
                .isEqualTo(RecordingFilter.OwnerScope.MINE);
        assertThat(of(null, null, false, null, null, null, "SHARED").ownerScope())
                .isEqualTo(RecordingFilter.OwnerScope.SHARED);
        assertThat(of(null, null, false, null, null, null, "all").ownerScope())
                .isEqualTo(RecordingFilter.OwnerScope.ALL);

        UUID kollege = UUID.randomUUID();
        RecordingFilter filter = of(null, null, false, null, null, null, kollege.toString());
        assertThat(filter.ownerId()).isEqualTo(kollege);
        assertThat(filter.ownerScope()).isEqualTo(RecordingFilter.OwnerScope.ALL);
    }

    @Test
    void weistEinenUnbrauchbarenBesitzerfilterAb() {
        assertThatThrownBy(() -> of(null, null, false, null, null, null, "kollege"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unbekannter Besitzerfilter");
    }
}
