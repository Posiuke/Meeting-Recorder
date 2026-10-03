-- Namensvorschlaege fuer erkannte Sprecher.
--
-- Bei Bot-Aufnahmen protokolliert der Bot die Sprechanzeige von BBB (wer zu
-- welcher Sekunde gesprochen hat, relativ zum Aufnahmestart). Abgeglichen mit
-- den Zeiten der Diarisierung ergibt das einen sehr sicheren Namen je Sprecher.
-- Ohne dieses Protokoll (Uploads, Bildschirmaufnahmen) schlaegt ein LLM Namen
-- anhand des Gespraechs vor ("Ich bin Anna", "Was meinst du, Tom?").
--
-- Ein Vorschlag wird nie still uebernommen (ausser speakers.autoApply ist an
-- und die Sicherheit hoch) - der Besitzer bestaetigt oder verwirft ihn.
ALTER TABLE recording ADD COLUMN talk_log TEXT;

ALTER TABLE participant ADD COLUMN suggested_name VARCHAR(255);
-- HIGH / MEDIUM / LOW
ALTER TABLE participant ADD COLUMN suggestion_confidence VARCHAR(16);
-- Woher der Vorschlag stammt: BBB (Sprechanzeige) oder LLM (Gespraechsinhalt)
ALTER TABLE participant ADD COLUMN suggestion_source VARCHAR(16);
-- Beleg, z.B. "[01:12] Hallo, ich bin Anna" oder "BBB-Sprechanzeige: 87 % der Redezeit"
ALTER TABLE participant ADD COLUMN suggestion_evidence TEXT;
