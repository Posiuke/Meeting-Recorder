-- Herkunft einer Aufnahme. Bisher wurde "Upload" daraus abgeleitet, dass weder
-- Bot-Session noch Meeting-URL gesetzt sind - mit der Bildschirmaufnahme im
-- Browser gibt es eine dritte Quelle, die genauso aussieht. Deshalb explizit.
ALTER TABLE recording ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'BOT';

-- Bestandsdaten nachziehen: Upload-Aufnahmen entstanden ohne Bot-Session und ohne Meeting-URL.
UPDATE recording SET source = 'UPLOAD' WHERE bot_session_id IS NULL AND meeting_url IS NULL;

-- Zeitpunkt des zuletzt empfangenen Chunks einer Browser-Aufnahme. Damit erkennt
-- der Sweeper abgebrochene Aufnahmen (Tab geschlossen, Rechner zugeklappt) und
-- rettet die bis dahin uebertragenen Daten, statt sie haengen zu lassen.
ALTER TABLE recording ADD COLUMN capture_last_chunk_at TIMESTAMPTZ;

CREATE INDEX idx_recording_source_status ON recording(source, status);
