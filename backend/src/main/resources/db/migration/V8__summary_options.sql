-- Pro-Aufnahme-Einstellungen fuer die KI-Zusammenfassung (NULL = Admin-Standard).
-- Damit lassen sich z.B. Nicht-Meeting-Inhalte (Vortrag, Interview, Sprachnotiz)
-- mit passendem Prompt, eigener Maximallaenge und Sprache auswerten.
ALTER TABLE recording ADD COLUMN summary_prompt TEXT;
ALTER TABLE recording ADD COLUMN summary_max_words INTEGER;
ALTER TABLE recording ADD COLUMN summary_language VARCHAR(16);
