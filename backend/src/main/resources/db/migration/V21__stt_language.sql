-- Sprache der Spracherkennung pro Aufnahme (NULL = Admin-Standard
-- whisper.language, "auto" = Whisper erkennt die Sprache selbst).
-- Bisher galt der Admin-Wert fuer alle Aufnahmen: Ein englisches Interview lief
-- damit mit deutschem Sprach-Hinweis durch Whisper - und ein beschaedigtes
-- Transkript retten weder Glaettung noch Zusammenfassung.
ALTER TABLE recording ADD COLUMN stt_language VARCHAR(16);

-- Wunsch aus dem Bot-Formular: Er gilt fuer jede Aufnahme dieser Bot-Session.
ALTER TABLE bot_session ADD COLUMN stt_language VARCHAR(16);
