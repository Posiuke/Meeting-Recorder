-- Schlagworte je Aufnahme. Sie gehoeren zur Aufnahme (nicht zum Nutzer): Der
-- Besitzer pflegt sie, alle mit Leseberechtigung sehen und durchsuchen sie.
--
-- name     = Anzeigeform, wie der Nutzer sie eingegeben hat ("Projekt Nord")
-- name_key = normalisierte Kleinschreibung fuer Eindeutigkeit und Suche
--            (bewusst eine eigene Spalte statt eines Funktionsindex, damit die
--            Migration auch unter H2 in den Tests laeuft)
CREATE TABLE recording_tag (
    id           UUID PRIMARY KEY,
    recording_id UUID NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    name         VARCHAR(64) NOT NULL,
    name_key     VARCHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (recording_id, name_key)
);

CREATE INDEX idx_recording_tag_recording ON recording_tag(recording_id);
CREATE INDEX idx_recording_tag_key ON recording_tag(name_key);
