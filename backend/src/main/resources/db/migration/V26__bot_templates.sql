-- Persoenliche Bot-Vorlagen (Issue #27): Wer denselben Raum regelmaessig
-- aufzeichnet, legt Meeting-URL, Bot-Name und Einstellungen einmal ab und
-- startet danach nur noch "Vorlage waehlen, Bot starten".
--
-- Bewusst benutzerbezogen wie prompt_template: In der Meeting-URL steckt der
-- Zugang zum Raum - eine Vorlage darf deshalb niemand anderes sehen. Loescht
-- ein Admin den Nutzer, gehen seine Vorlagen mit (ON DELETE CASCADE).
CREATE TABLE bot_template (
    id           UUID PRIMARY KEY,
    owner_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    meeting_url  TEXT NOT NULL,
    bot_name     VARCHAR(100) NOT NULL,
    auto_record  BOOLEAN NOT NULL,
    record_video BOOLEAN NOT NULL,
    ai_analysis  BOOLEAN NOT NULL,
    -- Wunsch des Nutzers, nicht die Freigabe: Ob die Sprechererkennung wirklich
    -- laeuft, entscheidet beim Start die Admin-Einstellung whisper.diarize.
    diarize      BOOLEAN NOT NULL,
    -- NULL = Admin-Standard whisper.language, "auto" = selbst erkennen
    stt_language VARCHAR(16),
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ
);
CREATE INDEX idx_bot_template_owner ON bot_template(owner_id);

-- Wie bei den Promptvorlagen: Namen sind pro Nutzer eindeutig (case-insensitive),
-- damit parallele Anlagen keine Duplikate erzeugen (TOCTOU).
CREATE UNIQUE INDEX uq_bot_template_owner_name
    ON bot_template(owner_id, lower(name));
