-- Geglaettete Fassung des Transkripts. Bewusst PRO SEGMENT neben dem Original:
-- Der TranscriptAssembler rechnet die Zeitstempel je Segment fortlaufend um,
-- und ein misslungenes Segment kostet so nur dieses eine.
ALTER TABLE recording_segment ADD COLUMN corrected_text TEXT;

-- Zustand der Glaettung fuer die Anzeige: NONE (nicht versucht), READY, FAILED.
ALTER TABLE recording ADD COLUMN correction_status VARCHAR(16);

-- Persoenliches Glossar: Abkuerzungen und Fachbegriffe, die in den eigenen
-- Besprechungen vorkommen. Jeder Nutzer pflegt seine eigene Liste; bei der
-- Glaettung wird das Glossar des Aufnahme-Besitzers verwendet.
--
-- term     = Schreibweise, wie der Nutzer sie eingegeben hat
-- term_key = normalisierte Kleinschreibung fuer die Eindeutigkeitspruefung
CREATE TABLE glossary_entry (
    id         UUID PRIMARY KEY,
    owner_id   UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    term       VARCHAR(200) NOT NULL,
    term_key   VARCHAR(200) NOT NULL,
    meaning    TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    UNIQUE (owner_id, term_key)
);

CREATE INDEX idx_glossary_owner ON glossary_entry(owner_id);
