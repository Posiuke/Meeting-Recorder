-- Dauer der einzelnen Verarbeitungsschritte je Auftrag (Issue #5).
--
-- Bisher liess sich nur die Gesamtdauer errechnen (started_at bis finished_at).
-- Fuer die Frage "warum hat die Nacht nicht gereicht?" ist das zu grob: Ob
-- Whisper langsam war oder das LLM, sind zwei ganz verschiedene Baustellen -
-- ein anderes Whisper-Modell hilft nicht gegen ein ueberlastetes LLM.
--
-- NULL heisst "dieser Schritt lief in diesem Auftrag nicht": Eine reine
-- Transkription hat keine Zusammenfassung, eine erneute Auswertung ohne
-- Whisper-Lauf keine Spracherkennung, und die Glaettung kann abgeschaltet sein.
ALTER TABLE processing_job ADD COLUMN stt_ms        BIGINT;
ALTER TABLE processing_job ADD COLUMN correction_ms BIGINT;
ALTER TABLE processing_job ADD COLUMN summary_ms    BIGINT;

-- Die Admin-Uebersicht liest die Schlange nach Status und die letzten
-- Fehlschlaege nach Abschlusszeit.
CREATE INDEX idx_processing_job_status_created ON processing_job(status, created_at);
