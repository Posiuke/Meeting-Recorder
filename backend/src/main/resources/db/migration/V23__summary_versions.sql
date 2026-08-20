-- Fassungen der Zusammenfassung statt Ueberschreiben: Eine erneute Auswertung
-- loescht die vorhandene Zusammenfassung nicht mehr, sondern legt eine weitere
-- Fassung daneben. Damit gehen von Hand bearbeitete Fassungen nicht verloren,
-- und zwei Vorlagen lassen sich an derselben Aufnahme vergleichen.

-- Woraus diese Fassung entstanden ist. Der Prompt steht dabei, weil genau das
-- die Frage beim Vergleich ist ("womit war die alte Fassung besser?") - der
-- Prompt der Aufnahme kann sich bis zur naechsten Auswertung geaendert haben.
ALTER TABLE summary ADD COLUMN template_name VARCHAR(200);
ALTER TABLE summary ADD COLUMN system_prompt TEXT;

-- Haendisch bearbeitet: macht in der Fassungsliste sichtbar, welche Fassung
-- Handarbeit enthaelt und darum nicht einfach ersetzt werden sollte.
ALTER TABLE summary ADD COLUMN edited_at TIMESTAMPTZ;

-- Die aktuelle Fassung. Sie ist die eine, die summary.md, der Download, die
-- API und die Freigabe-Ansicht zeigen; die uebrigen bleiben zum Vergleich
-- daneben stehen.
ALTER TABLE summary ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT FALSE;

-- Bestand: je Aufnahme wird die neueste fertige Fassung die aktuelle - genau
-- die, die bisher ueberall gezeigt wurde.
UPDATE summary SET is_current = TRUE WHERE id IN (
    SELECT DISTINCT ON (recording_id) id
      FROM summary
     WHERE status = 'DONE' AND markdown IS NOT NULL
     ORDER BY recording_id, created_at DESC);

-- Genau eine aktuelle Fassung je Aufnahme. Als Teil-Index, weil "nicht aktuell"
-- beliebig oft vorkommen darf.
CREATE UNIQUE INDEX uq_summary_current ON summary (recording_id) WHERE is_current;

-- Name der Vorlage, die fuer diese Aufnahme gewaehlt wurde (NULL = keine
-- benannte Vorlage). Der Prompt selbst steht schon in summary_prompt; der Name
-- ist das, was eine Fassung in der Auswahlliste unterscheidbar macht.
ALTER TABLE recording ADD COLUMN summary_template_name VARCHAR(200);

-- Der Auftrag ersetzt nichts mehr. Uebrig bleibt die Frage, ob bei seiner
-- Anlage schon eine fertige Auswertung vorlag - danach entscheidet sich, ob
-- eine erneute Transkription bei Schritt 1 stehen bleibt.
ALTER TABLE processing_job RENAME COLUMN replace_existing TO had_summary;
