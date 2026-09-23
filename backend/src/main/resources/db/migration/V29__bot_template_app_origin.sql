-- Adresse, unter der der Nutzer die Anwendung beim Speichern der Bot-Vorlage
-- aufgerufen hat (Origin des Browsers). Startet der Zeitplan den Bot, gibt es
-- keine Browser-Anfrage - der Bot baut seinen anonymen Stopp-Link dann aus
-- dieser Adresse, so wie die Oberflaeche Freigabe-Links aus
-- window.location.origin baut. NULL = unbekannt (vor V29 gespeichert).
ALTER TABLE bot_template ADD COLUMN app_origin VARCHAR(255);
