-- Persoenliche Promptvorlagen: jeder Nutzer kann eigene Auswertungs-Prompts
-- benennen und speichern und sie im Dialog "Auswertung anpassen" wiederverwenden.
CREATE TABLE prompt_template (
    id         UUID PRIMARY KEY,
    owner_id   UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    prompt     TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ
);
CREATE INDEX idx_prompt_template_owner ON prompt_template(owner_id);
