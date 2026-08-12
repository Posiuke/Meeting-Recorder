-- Oeffentliche Freigabe-Links: Wer die Adresse kennt, sieht Video, Audio,
-- Transkript und Zusammenfassung einer Aufnahme ohne Anmeldung.
--
-- token      = Zugriffsmerkmal im Klartext (32 Byte Zufall, base64url). Bewusst
--              nicht gehasht wie api_key.token_hash: Der Besitzer soll den Link
--              auch spaeter noch kopieren koennen.
-- expires_at = NULL bedeutet gueltig bis zum Widerruf (Loeschen der Zeile)
-- views      = Anzahl der Aufrufe der Freigabe-Ansicht (Auskunft fuer den Besitzer)
CREATE TABLE share_link (
    id             UUID PRIMARY KEY,
    recording_id   UUID NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    token          VARCHAR(64) NOT NULL UNIQUE,
    created_by     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ,
    last_viewed_at TIMESTAMPTZ,
    views          INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_share_link_recording ON share_link(recording_id);
