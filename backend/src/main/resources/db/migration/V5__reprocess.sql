-- Erneute Auswertung: Job-Flag, ob die vorhandenen Zusammenfassungen nach
-- erfolgreichem Lauf ersetzt werden sollen.
ALTER TABLE processing_job ADD COLUMN replace_existing BOOLEAN NOT NULL DEFAULT FALSE;
