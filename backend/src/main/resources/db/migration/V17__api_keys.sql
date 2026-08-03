-- Persoenliche API-Schluessel: erlauben Skripten dieselben Aufrufe wie die
-- Weboberflaeche. Gespeichert wird nur der SHA-256-Abdruck (token_hash), der
-- Schluessel selbst wird einmal beim Anlegen angezeigt.
--
-- token_prefix = Anfang des Schluessels zur Wiedererkennung in der Liste
-- read_only    = nur GET-Anfragen erlaubt (Auswerte-Skripte)
-- expires_at   = NULL bedeutet unbegrenzt gueltig
CREATE TABLE api_key (
    id           UUID PRIMARY KEY,
    owner_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name         VARCHAR(120) NOT NULL,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    token_prefix VARCHAR(24) NOT NULL,
    read_only    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_api_key_owner ON api_key(owner_id);
