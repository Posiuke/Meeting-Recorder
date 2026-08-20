-- Beigefuegte Unterlagen einer Aufnahme (Tagesordnung, Folien, Papiere). Ihr
-- Text geht in die Auswertung ein, damit die Zusammenfassung das Thema kennt
-- und nicht nur das Gesprochene.
--
-- Der extrahierte Text steht in der Datenbank und nicht nur in der Datei: Die
-- Extraktion laeuft einmal (bei OCR dauert sie Minuten), gelesen wird sie bei
-- jeder Auswertung.
CREATE TABLE recording_document (
    id            UUID PRIMARY KEY,
    recording_id  UUID NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    -- Originalname, wie der Nutzer die Datei hochgeladen hat (Anzeige und Prompt)
    filename      VARCHAR(255) NOT NULL,
    -- Ablage im Aufnahme-Verzeichnis; die Datei bleibt herunterladbar
    stored_path   TEXT NOT NULL,
    content_type  VARCHAR(255),
    size_bytes    BIGINT NOT NULL,
    -- PENDING (Extraktion laeuft/wartet), READY, FAILED
    status        VARCHAR(16) NOT NULL,
    extracted_text TEXT,
    -- Zeichen des extrahierten Textes; macht in der Liste sichtbar, ob wirklich
    -- Text herauskam (ein Scan ohne OCR liefert nichts)
    text_chars    INTEGER,
    error         TEXT,
    uploaded_by   UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    extracted_at  TIMESTAMPTZ
);

CREATE INDEX idx_document_recording ON recording_document(recording_id);
