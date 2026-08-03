-- Vom Bot aus der BBB-Oberflaeche erkannter Raum-/Meetingname
ALTER TABLE bot_session ADD COLUMN room_name VARCHAR(512);
