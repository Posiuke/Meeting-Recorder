package bbbbot.llm;

import bbbbot.domain.GlossaryEntry;
import bbbbot.repository.Repositories.GlossaryEntryRepo;
import bbbbot.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlossaryServiceTest {

    private final UUID owner = UUID.randomUUID();

    /** Grosszuegige Grenze wie im Standard - hier soll nichts abgeschnitten werden. */
    private static final int NO_CAP = 0;

    @Test
    void rendertBegriffMitUndOhneBedeutung() {
        String block = GlossaryService.renderPromptBlock(List.of(
                GlossaryEntry.create(owner, "RZ", "Rechenzentrum"),
                GlossaryEntry.create(owner, "Jour Fixe", null)), NO_CAP);

        assertThat(block).isEqualTo("""
                - RZ = Rechenzentrum
                - Jour Fixe
                """);
    }

    @Test
    void leeresGlossarErgibtLeerenBlock() {
        assertThat(GlossaryService.renderPromptBlock(List.of(), NO_CAP)).isEmpty();
    }

    @Test
    void ueberspringtLeereBegriffe() {
        String block = GlossaryService.renderPromptBlock(List.of(
                GlossaryEntry.create(owner, "   ", "wird ignoriert"),
                GlossaryEntry.create(owner, "Gültig", null)), NO_CAP);

        assertThat(block).isEqualTo("- Gültig\n");
    }

    @Test
    void haeltDieEingestellteObergrenzeEin() {
        List<GlossaryEntry> many = manyEntries(500);

        String block = GlossaryService.renderPromptBlock(many, 2000);

        assertThat(block.length()).isLessThanOrEqualTo(2000);
        // Abgeschnitten wird zwischen Eintraegen, nicht mitten in einem
        assertThat(block).endsWith("\n");
    }

    @Test
    void ohneObergrenzeGehtDasGanzeGlossarMit() {
        List<GlossaryEntry> many = manyEntries(500);

        String block = GlossaryService.renderPromptBlock(many, NO_CAP);

        assertThat(block.lines()).hasSize(500);
        assertThat(block.length()).isGreaterThan(50_000);
    }

    private List<GlossaryEntry> manyEntries(int count) {
        List<GlossaryEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(GlossaryEntry.create(owner, "Begriff-" + i, "x".repeat(100)));
        }
        return entries;
    }

    /**
     * Import: Das Glossar wird zusammengefuehrt, nie geloescht. Der Repo-Ersatz
     * haelt die Eintraege in einer Liste, damit die Zaehlung im Ergebnis pruefbar
     * ist.
     */
    @Nested
    class Import {

        private final List<GlossaryEntry> stored = new ArrayList<>();
        private GlossaryService service;

        @BeforeEach
        void setup() {
            GlossaryEntryRepo repo = mock(GlossaryEntryRepo.class);
            service = new GlossaryService(repo, mock(SettingsService.class));

            when(repo.save(any(GlossaryEntry.class))).thenAnswer(inv -> {
                GlossaryEntry entry = inv.getArgument(0);
                if (!stored.contains(entry)) stored.add(entry);
                return entry;
            });
            when(repo.countByOwnerId(owner)).thenAnswer(inv -> (long) stored.size());
            when(repo.findByOwnerIdAndTermKey(eq(owner), anyString())).thenAnswer(inv -> {
                String key = inv.getArgument(1);
                return stored.stream().filter(e -> e.getTermKey().equals(key)).findFirst();
            });
        }

        private GlossaryService.ImportResult importText(String csv) {
            return service.importCsv(owner, csv.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void legtNeueBegriffeAn() {
            GlossaryService.ImportResult result = importText("""
                    Begriff;Bedeutung
                    BBB;BigBlueButton
                    Jour Fixe;
                    """);

            assertThat(result.created()).isEqualTo(2);
            assertThat(result.updated()).isZero();
            assertThat(result.warnings()).isEmpty();
            assertThat(stored).extracting(GlossaryEntry::getTerm).containsExactly("BBB", "Jour Fixe");
            assertThat(stored).extracting(GlossaryEntry::getMeaning)
                    .containsExactly("BigBlueButton", null);
        }

        @Test
        void aktualisiertVorhandeneUndLaesstUebrigeStehen() {
            stored.add(GlossaryEntry.create(owner, "BBB", "alte Bedeutung"));
            stored.add(GlossaryEntry.create(owner, "Altbegriff", "bleibt"));

            GlossaryService.ImportResult result = importText("""
                    Begriff;Bedeutung
                    BBB;BigBlueButton
                    STT;Spracherkennung
                    """);

            assertThat(result.created()).isEqualTo(1);
            assertThat(result.updated()).isEqualTo(1);
            assertThat(result.unchanged()).isZero();
            assertThat(stored).extracting(GlossaryEntry::getTerm)
                    .containsExactly("BBB", "Altbegriff", "STT");
            assertThat(stored.get(0).getMeaning()).isEqualTo("BigBlueButton");
            // Ein Import darf nichts entfernen, was nicht in der Datei steht
            assertThat(stored.get(1).getMeaning()).isEqualTo("bleibt");
        }

        @Test
        void zaehltUnveraenderteEintraegeGetrennt() {
            stored.add(GlossaryEntry.create(owner, "BBB", "BigBlueButton"));

            GlossaryService.ImportResult result = importText("Begriff;Bedeutung\nBBB;BigBlueButton\n");

            assertThat(result.unchanged()).isEqualTo(1);
            assertThat(result.created()).isZero();
            assertThat(result.updated()).isZero();
        }

        @Test
        void uebernimmtNeueSchreibweiseDesGleichenBegriffs() {
            stored.add(GlossaryEntry.create(owner, "bbb", "BigBlueButton"));

            GlossaryService.ImportResult result = importText("Begriff;Bedeutung\nBBB;BigBlueButton\n");

            assertThat(result.updated()).isEqualTo(1);
            assertThat(stored).singleElement()
                    .satisfies(e -> assertThat(e.getTerm()).isEqualTo("BBB"));
        }

        @Test
        void ueberspringtZuLangeFelderMitHinweis() {
            String longTerm = "T".repeat(GlossaryEntry.MAX_TERM_LENGTH + 1);
            String longMeaning = "B".repeat(GlossaryEntry.MAX_MEANING_LENGTH + 1);

            GlossaryService.ImportResult result = importText("Begriff;Bedeutung\n"
                    + longTerm + ";kurz\n"
                    + "STT;" + longMeaning + "\n"
                    + "BBB;BigBlueButton\n");

            assertThat(result.created()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(2);
            assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("Zeile 2", "zu lang"))
                    .anySatisfy(w -> assertThat(w).contains("Zeile 3", "zu lang"));
            assertThat(stored).extracting(GlossaryEntry::getTerm).containsExactly("BBB");
        }

        @Test
        void kuerztDieHinweislisteAuchBeiVielenFehlern() {
            StringBuilder csv = new StringBuilder("Begriff;Bedeutung\n");
            for (int i = 0; i < GlossaryService.MAX_WARNINGS + 10; i++) {
                csv.append(";Bedeutung ohne Begriff\n");
            }

            GlossaryService.ImportResult result = importText(csv.toString());

            assertThat(result.warnings()).hasSize(GlossaryService.MAX_WARNINGS + 1);
            assertThat(result.warnings().getLast()).contains("10 weitere");
        }
    }

    /**
     * Gemeinsames Glossar der Installation + persoenliches des Aufnahme-Besitzers:
     * beide gehen in den Prompt ein, bei gleichem Begriff gewinnt das persoenliche.
     */
    @Nested
    class Zusammenfuehrung {

        private final UUID other = UUID.randomUUID();

        @Test
        void beideListenGehenEinUndBleibenAlphabetisch() {
            List<GlossaryEntry> merged = GlossaryService.merge(
                    List.of(GlossaryEntry.create(null, "RZ", "Rechenzentrum"),
                            GlossaryEntry.create(null, "Alpha", "Projekt Alpha")),
                    List.of(GlossaryEntry.create(owner, "Jour Fixe", "Wochenrunde")));

            assertThat(merged).extracting(GlossaryEntry::getTerm)
                    .containsExactly("Alpha", "Jour Fixe", "RZ");
        }

        @Test
        void beiGleichemBegriffGewinntDerPersoenlicheEintrag() {
            List<GlossaryEntry> merged = GlossaryService.merge(
                    List.of(GlossaryEntry.create(null, "RZ", "Rechenzentrum")),
                    List.of(GlossaryEntry.create(owner, "rz", "Rechtsabteilung Zentrale")));

            assertThat(merged).singleElement().satisfies(e -> {
                assertThat(e.getTerm()).isEqualTo("rz");
                assertThat(e.getMeaning()).isEqualTo("Rechtsabteilung Zentrale");
                assertThat(e.getOwnerId()).isEqualTo(owner);
            });
        }

        @Test
        void promptBlockNimmtGemeinsamesUndPersoenlichesGlossar() {
            GlossaryEntryRepo repo = mock(GlossaryEntryRepo.class);
            SettingsService settings = mock(SettingsService.class);
            when(settings.getInt(SettingsService.CORRECTION_GLOSSARY_MAX_CHARS)).thenReturn(NO_CAP);
            when(repo.findByOwnerIdIsNullOrderByTermKeyAsc()).thenReturn(
                    List.of(GlossaryEntry.create(null, "Alpha", "Projekt Alpha"),
                            GlossaryEntry.create(null, "RZ", "Rechenzentrum")));
            when(repo.findByOwnerIdOrderByTermKeyAsc(owner)).thenReturn(
                    List.of(GlossaryEntry.create(owner, "RZ", "Rechtsabteilung")));

            String block = new GlossaryService(repo, settings).promptBlock(owner);

            assertThat(block).isEqualTo("""
                    - Alpha = Projekt Alpha
                    - RZ = Rechtsabteilung
                    """);
        }

        /** Die Obergrenze gilt fuer das Ergebnis, nicht je Liste. */
        @Test
        void obergrenzeGiltFuerDasZusammengefuehrteErgebnis() {
            GlossaryEntryRepo repo = mock(GlossaryEntryRepo.class);
            SettingsService settings = mock(SettingsService.class);
            when(settings.getInt(SettingsService.CORRECTION_GLOSSARY_MAX_CHARS)).thenReturn(45);
            when(repo.findByOwnerIdIsNullOrderByTermKeyAsc()).thenReturn(
                    List.of(GlossaryEntry.create(null, "Alpha", "A".repeat(30))));
            when(repo.findByOwnerIdOrderByTermKeyAsc(owner)).thenReturn(
                    List.of(GlossaryEntry.create(owner, "Beta", "B".repeat(30))));

            String block = new GlossaryService(repo, settings).promptBlock(owner);

            assertThat(block.length()).isLessThanOrEqualTo(45);
            assertThat(block).contains("Alpha").doesNotContain("Beta");
        }

        /** Das Glossar eines anderen Nutzers hat mit dieser Aufnahme nichts zu tun. */
        @Test
        void fremdeGlossareBleibenAussen() {
            GlossaryEntryRepo repo = mock(GlossaryEntryRepo.class);
            SettingsService settings = mock(SettingsService.class);
            when(settings.getInt(SettingsService.CORRECTION_GLOSSARY_MAX_CHARS)).thenReturn(NO_CAP);
            when(repo.findByOwnerIdIsNullOrderByTermKeyAsc()).thenReturn(List.of());
            when(repo.findByOwnerIdOrderByTermKeyAsc(owner)).thenReturn(List.of());
            when(repo.findByOwnerIdOrderByTermKeyAsc(other)).thenReturn(
                    List.of(GlossaryEntry.create(other, "Fremd", "nicht hier")));

            assertThat(new GlossaryService(repo, settings).promptBlock(owner)).isEmpty();
        }
    }

    /** Derselbe CSV-Import fuellt auch das gemeinsame Glossar (Eintraege ohne Besitzer). */
    @Test
    void importInsGemeinsameGlossarLegtEintraegeOhneBesitzerAn() {
        GlossaryEntryRepo repo = mock(GlossaryEntryRepo.class);
        List<GlossaryEntry> stored = new ArrayList<>();
        when(repo.save(any(GlossaryEntry.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repo.countByOwnerIdIsNull()).thenAnswer(inv -> (long) stored.size());
        when(repo.findByOwnerIdIsNullAndTermKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return stored.stream().filter(e -> e.getTermKey().equals(key)).findFirst();
        });
        GlossaryService service = new GlossaryService(repo, mock(SettingsService.class));

        GlossaryService.ImportResult result = service.importCsv(null,
                "Begriff;Bedeutung\nBBB;BigBlueButton\n".getBytes(StandardCharsets.UTF_8));

        assertThat(result.created()).isEqualTo(1);
        assertThat(stored).singleElement().satisfies(e -> {
            assertThat(e.getTerm()).isEqualTo("BBB");
            assertThat(e.isShared()).isTrue();
        });
    }

    @Test
    void vergleichsformIgnoriertSchreibweiseUndRandLeerzeichen() {
        assertThat(GlossaryEntry.normalizeKey("  RZ ")).isEqualTo("rz");
        GlossaryEntry entry = GlossaryEntry.create(owner, "  Jour Fixe  ", null);
        assertThat(entry.getTerm()).isEqualTo("Jour Fixe");
        assertThat(entry.getTermKey()).isEqualTo("jour fixe");
    }
}
