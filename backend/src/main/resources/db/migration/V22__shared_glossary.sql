-- Gemeinsames Glossar der ganzen Installation, zusaetzlich zum persoenlichen:
-- Abteilungskuerzel, Projekt- und Produktnamen sind kein persoenlicher Besitz.
-- Gepflegt wird es von Admins; in die Glaettung geht es zusammen mit dem
-- persoenlichen Glossar des Aufnahme-Besitzers ein (dort gewinnt bei gleichem
-- Begriff der persoenliche Eintrag).
--
-- Kennzeichen eines gemeinsamen Eintrags ist owner_id IS NULL - kein eigener
-- Geltungsbereich als zweite Spalte, die zum Besitzer widersprechen koennte.
ALTER TABLE glossary_entry ALTER COLUMN owner_id DROP NOT NULL;

-- Die Eindeutigkeit haengt am Geltungsbereich: je Nutzer einmal, fuer die
-- Installation einmal. Die Unique-Klausel aus V15 kann das nicht ausdruecken,
-- weil NULL in Postgres als verschieden von NULL gilt - zwei Teil-Indexe koennen es.
ALTER TABLE glossary_entry DROP CONSTRAINT IF EXISTS glossary_entry_owner_id_term_key_key;
CREATE UNIQUE INDEX uq_glossary_personal_term
    ON glossary_entry (owner_id, term_key) WHERE owner_id IS NOT NULL;
CREATE UNIQUE INDEX uq_glossary_shared_term
    ON glossary_entry (term_key) WHERE owner_id IS NULL;
