package bbbbot.llm;

import bbbbot.domain.GlossaryEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class GlossaryCsvTest {

    private final UUID owner = UUID.randomUUID();

    private static String text(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8);
    }

    @Test
    void exportSchreibtKopfzeileUndBom() {
        String csv = text(GlossaryCsv.export(List.of()));

        // Ohne BOM zeigt Excel Umlaute falsch an - das leere Glossar dient als Vorlage
        assertThat(csv).isEqualTo("﻿Begriff;Bedeutung\r\n");
    }

    @Test
    void exportSchreibtBegriffUndBedeutung() {
        String csv = text(GlossaryCsv.export(List.of(
                GlossaryEntry.create(owner, "RZ", "Rechenzentrum"),
                GlossaryEntry.create(owner, "Jour Fixe", null))));

        assertThat(csv).isEqualTo("﻿Begriff;Bedeutung\r\n"
                + "RZ;Rechenzentrum\r\n"
                + "Jour Fixe;\r\n");
    }

    @Test
    void exportSetztAnfuehrungszeichenNurWoNoetig() {
        String csv = text(GlossaryCsv.export(List.of(
                GlossaryEntry.create(owner, "STT", "Speech-to-Text; Spracherkennung"),
                GlossaryEntry.create(owner, "Zoll", "Sagt \"Zoll\", gemeint ist der Zollhof"),
                GlossaryEntry.create(owner, "Mehrzeilig", "Erste Zeile\nZweite Zeile"))));

        assertThat(csv).contains("STT;\"Speech-to-Text; Spracherkennung\"\r\n");
        assertThat(csv).contains("Zoll;\"Sagt \"\"Zoll\"\", gemeint ist der Zollhof\"\r\n");
        assertThat(csv).contains("Mehrzeilig;\"Erste Zeile\nZweite Zeile\"\r\n");
    }

    @Test
    void exportUndImportPassenZusammen() {
        List<GlossaryEntry> entries = List.of(
                GlossaryEntry.create(owner, "STT", "Speech-to-Text; Spracherkennung"),
                GlossaryEntry.create(owner, "Zoll", "Sagt \"Zoll\""),
                GlossaryEntry.create(owner, "Mehrzeilig", "Erste Zeile\nZweite Zeile"),
                GlossaryEntry.create(owner, "Jour Fixe", null));

        byte[] file = GlossaryCsv.export(entries);
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse(GlossaryCsv.decode(file));

        assertThat(parsed.notes()).isEmpty();
        assertThat(parsed.skipped()).isZero();
        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::term, GlossaryCsv.Row::meaning)
                .containsExactly(
                        tuple("STT", "Speech-to-Text; Spracherkennung"),
                        tuple("Zoll", "Sagt \"Zoll\""),
                        tuple("Mehrzeilig", "Erste Zeile\nZweite Zeile"),
                        tuple("Jour Fixe", null));
    }

    @Test
    void liestHandgeschriebeneDateiMitKommentarenUndLeerzeilen() {
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse("""
                # Mein Glossar, von Hand gepflegt
                Begriff;Bedeutung

                BBB;BigBlueButton
                # noch offen: Abkuerzung XY klaeren
                Jour Fixe
                """);

        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::term)
                .containsExactly("BBB", "Jour Fixe");
        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::meaning)
                .containsExactly("BigBlueButton", null);
        assertThat(parsed.notes()).isEmpty();
    }

    @Test
    void erkenntKommaUndTabulatorAlsTrennzeichen() {
        GlossaryCsv.ParseResult komma = GlossaryCsv.parse("Term,Meaning\nBBB,BigBlueButton\n");
        GlossaryCsv.ParseResult tab = GlossaryCsv.parse("Begriff\tBedeutung\nBBB\tBigBlueButton\n");

        assertThat(komma.rows()).extracting(GlossaryCsv.Row::term).containsExactly("BBB");
        assertThat(komma.rows()).extracting(GlossaryCsv.Row::meaning).containsExactly("BigBlueButton");
        assertThat(tab.rows()).extracting(GlossaryCsv.Row::meaning).containsExactly("BigBlueButton");
    }

    @Test
    void ohneKopfzeileGehtDieErsteZeileMit() {
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse("BBB;BigBlueButton\nSTT;Spracherkennung\n");

        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::term).containsExactly("BBB", "STT");
    }

    @Test
    void meldetZeileOhneBegriff() {
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse("""
                Begriff;Bedeutung
                BBB;BigBlueButton
                ;Bedeutung ohne Begriff
                """);

        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::term).containsExactly("BBB");
        assertThat(parsed.skipped()).isEqualTo(1);
        assertThat(parsed.notes()).containsExactly("Zeile 3: kein Begriff angegeben");
    }

    @Test
    void fasstMehrfachGenanntenBegriffZusammenUndNimmtDieSpaetereZeile() {
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse("""
                Begriff;Bedeutung
                BBB;alte Bedeutung
                bbb;neue Bedeutung
                """);

        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::term, GlossaryCsv.Row::meaning)
                .containsExactly(tuple("bbb", "neue Bedeutung"));
        assertThat(parsed.skipped()).isEqualTo(1);
        assertThat(parsed.notes()).singleElement().asString()
                .contains("Zeile 3", "steht schon in Zeile 2");
    }

    @Test
    void ignoriertZusaetzlicheSpaltenUndSagtEs() {
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse(
                "Begriff;Bedeutung;Notiz\nBBB;BigBlueButton;pruefen\n");

        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::meaning).containsExactly("BigBlueButton");
        assertThat(parsed.notes()).singleElement().asString().contains("Zusaetzliche Spalten");
    }

    @Test
    void liestMehrzeiligeBedeutungUndZaehltZeilenWeiter() {
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse("""
                Begriff;Bedeutung
                Ablauf;"Erst A
                dann B"
                ;ohne Begriff
                """);

        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::meaning)
                .containsExactly("Erst A\ndann B");
        // Die Zeilennummer im Hinweis muss die physische Zeile der Datei sein
        assertThat(parsed.notes()).containsExactly("Zeile 4: kein Begriff angegeben");
    }

    @Test
    void meldetOffenesAnfuehrungszeichen() {
        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse("Begriff;Bedeutung\nBBB;\"nicht geschlossen\n");

        assertThat(parsed.notes()).singleElement().asString().contains("nicht geschlossen");
    }

    @Test
    void entschluesseltWindows1252AlsRueckfall() {
        byte[] ansi = "Begriff;Bedeutung\nGrün;Farbe\n".getBytes(Charset.forName("windows-1252"));

        GlossaryCsv.ParseResult parsed = GlossaryCsv.parse(GlossaryCsv.decode(ansi));

        assertThat(parsed.rows()).extracting(GlossaryCsv.Row::term).containsExactly("Grün");
    }

    @Test
    void erkenntBinaerdateien() {
        assertThat(GlossaryCsv.looksBinary(new byte[]{'P', 'K', 3, 4, 0, 0})).isTrue();
        assertThat(GlossaryCsv.looksBinary("Begriff;Bedeutung\n".getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}
