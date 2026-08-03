-- Video-Aufnahme (Browser-Ansicht als MP4) und schaltbare KI-Analyse.
-- record_video: Bot zeichnet die Browser-Ansicht auf und muxt sie mit dem Audio zu MP4.
-- ai_analysis: ob nach der Aufnahme STT (Whisper) + Zusammenfassung (LLM) laufen soll.

ALTER TABLE bot_session
    ADD COLUMN ai_analysis BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE recording
    ADD COLUMN record_video BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ai_analysis  BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN video_path   TEXT,
    ADD COLUMN video_status VARCHAR(32);
