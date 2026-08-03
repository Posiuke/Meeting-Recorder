-- DB-seitige Absicherung der App-Pruefung: Vorlagennamen sind pro Nutzer
-- eindeutig (case-insensitive) - parallele Anlagen koennen sonst Duplikate
-- erzeugen (TOCTOU zwischen exists-Check und Insert).
CREATE UNIQUE INDEX uq_prompt_template_owner_name
    ON prompt_template(owner_id, lower(name));
