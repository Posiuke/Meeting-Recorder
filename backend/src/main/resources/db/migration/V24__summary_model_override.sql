-- Modell und Temperatur je Vorlage bzw. je Aufnahme, zusaetzlich zu den
-- globalen Admin-Vorgaben (llm.model, llm.temperature). Erst damit lassen sich
-- zwei Modelle an DERSELBEN Aufnahme vergleichen: Vorlage waehlen, auswerten,
-- Vorlage wechseln, erneut auswerten - die Fassungen (V23) stehen danach
-- nebeneinander.
--
-- NULL bedeutet ueberall "Vorgabe des Admins verwenden". Ein leerer String
-- waere hier kein guter zweiter Weg fuer dasselbe, darum bleibt es bei NULL.
ALTER TABLE prompt_template ADD COLUMN model VARCHAR(200);
ALTER TABLE prompt_template ADD COLUMN temperature DOUBLE PRECISION;

ALTER TABLE recording ADD COLUMN summary_model VARCHAR(200);
ALTER TABLE recording ADD COLUMN summary_temperature DOUBLE PRECISION;

-- Womit diese Fassung entstanden ist. Das Modell steht schon in summary.model;
-- die Temperatur gehoert dazu, sonst sind zwei Fassungen desselben Modells
-- nicht auseinanderzuhalten.
ALTER TABLE summary ADD COLUMN temperature DOUBLE PRECISION;
