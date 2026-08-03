-- Zwei-Schritt-Auswertung: Job-Flag, ob nur transkribiert werden soll
-- (die KI-Zusammenfassung wird dann separat manuell angestossen).
ALTER TABLE processing_job ADD COLUMN transcribe_only BOOLEAN NOT NULL DEFAULT FALSE;
