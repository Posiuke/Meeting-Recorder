-- Oberflaechensprache je Nutzer. NULL = noch nichts gewaehlt; das Frontend
-- nimmt dann die Browsersprache und faellt auf Deutsch zurueck.
ALTER TABLE app_user ADD COLUMN language VARCHAR(8);
