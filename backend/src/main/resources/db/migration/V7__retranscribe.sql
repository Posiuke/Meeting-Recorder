-- Erneute Transkription: Job-Flag, ob vorhandene Segment-Transkripte neu
-- erstellt werden sollen (Spracherkennung laeuft dann erneut ueber alle Segmente).
ALTER TABLE processing_job ADD COLUMN redo_transcripts BOOLEAN NOT NULL DEFAULT FALSE;
