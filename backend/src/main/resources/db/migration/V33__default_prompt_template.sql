-- Persoenliche Standardvorlage fuer die Auswertung.
--
-- Wertet der Bot eine Aufnahme ohne gewaehlte Vorlage aus (Bot ohne Vorlage,
-- Upload mit "Standard", Bildschirmaufnahme, API), nimmt er die Standardvorlage
-- des Besitzers - erst ohne sie gilt die Vorgabe des Administrators.
-- Wird die Vorlage geloescht, faellt die Wahl von selbst weg.
ALTER TABLE app_user ADD COLUMN default_prompt_template_id UUID
    REFERENCES prompt_template(id) ON DELETE SET NULL;
