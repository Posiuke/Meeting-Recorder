-- Sprechererkennung (Diarisierung) pro Aufnahme waehlbar; greift nur, wenn
-- der Admin sie freigeschaltet hat (Setting whisper.diarize).
ALTER TABLE recording ADD COLUMN diarize BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bot_session ADD COLUMN diarize BOOLEAN NOT NULL DEFAULT FALSE;
