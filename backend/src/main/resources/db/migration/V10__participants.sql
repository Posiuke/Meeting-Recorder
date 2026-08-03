-- Teilnehmerliste pro Aufnahme: die von der Diarisierung erkannten Sprecher
-- (speaker_label, z.B. SPEAKER_00) werden nach der Transkription persistiert
-- und koennen vom Besitzer umbenannt werden (display_name).
CREATE TABLE participant (
    id            UUID PRIMARY KEY,
    recording_id  UUID NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    speaker_label TEXT,
    display_name  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_participant_recording ON participant(recording_id);
CREATE UNIQUE INDEX uq_participant_recording_label
    ON participant(recording_id, speaker_label) WHERE speaker_label IS NOT NULL;
