-- Bot-Vorlagen mit Zeitplan und Auswertungs-Vorlage.
--
-- Zeitplan: Der Bot tritt an den gewaehlten Wochentagen zur Startzeit selbst
-- bei und verlaesst den Raum zur Endzeit wieder (z.B. Mo/Mi/Fr 09:00-10:00).
-- Die Uhrzeiten gelten in schedule_time_zone (IANA-Name aus dem Browser des
-- Nutzers) - der Container laeuft haeufig in UTC, der Termin aber nicht.
-- Liegt die Endzeit vor der Startzeit, endet der Termin am Folgetag.
ALTER TABLE bot_template ADD COLUMN schedule_enabled BOOLEAN NOT NULL DEFAULT FALSE;
-- Komma-getrennte ISO-Wochentage (MONDAY,WEDNESDAY,...)
ALTER TABLE bot_template ADD COLUMN schedule_days VARCHAR(80);
ALTER TABLE bot_template ADD COLUMN schedule_start TIME;
ALTER TABLE bot_template ADD COLUMN schedule_end TIME;
ALTER TABLE bot_template ADD COLUMN schedule_time_zone VARCHAR(64);

-- Auswertungs-Vorlage: summary_preset ist die Auswahl der Oberflaeche
-- (NULL = Admin-Standard, 'tpl:<id>' = eigene Promptvorlage, sonst Schluessel
-- einer integrierten Vorlage). Prompt, Name, Modell und Temperatur sind der
-- Stand beim Speichern; eine eigene Promptvorlage wird beim Start frisch
-- gelesen, der gespeicherte Stand greift nur, wenn sie inzwischen geloescht ist.
ALTER TABLE bot_template ADD COLUMN summary_preset VARCHAR(64);
ALTER TABLE bot_template ADD COLUMN summary_prompt TEXT;
ALTER TABLE bot_template ADD COLUMN summary_template_name VARCHAR(200);
ALTER TABLE bot_template ADD COLUMN summary_model VARCHAR(200);
ALTER TABLE bot_template ADD COLUMN summary_temperature DOUBLE PRECISION;

-- Die Session haelt die Auswertungs-Vorlage bis zum Aufnahmestart fest (eine
-- Session kann mehrere Aufnahmen erzeugen) und merkt sich, aus welcher Vorlage
-- sie stammt und wann der Zeitplan sie beendet.
ALTER TABLE bot_session ADD COLUMN summary_prompt TEXT;
ALTER TABLE bot_session ADD COLUMN summary_template_name VARCHAR(200);
ALTER TABLE bot_session ADD COLUMN summary_model VARCHAR(200);
ALTER TABLE bot_session ADD COLUMN summary_temperature DOUBLE PRECISION;
ALTER TABLE bot_session ADD COLUMN bot_template_id UUID
    REFERENCES bot_template(id) ON DELETE SET NULL;
ALTER TABLE bot_session ADD COLUMN scheduled_stop_at TIMESTAMPTZ;
CREATE INDEX idx_bot_session_template ON bot_session(bot_template_id, created_at);
CREATE INDEX idx_bot_template_schedule ON bot_template(schedule_enabled) WHERE schedule_enabled;
