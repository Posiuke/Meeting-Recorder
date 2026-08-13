-- Letzte Aktivitaet je Nutzer. Die Anmeldung ist zustandslos (JWT), deshalb gibt
-- es keine Sitzungstabelle: Der Zeitstempel wird bei jeder Anfrage mit gueltigem
-- Token fortgeschrieben (gedrosselt) und beantwortet damit im Admin-Bereich die
-- Frage, wer gerade angemeldet ist. NULL = seit der Einfuehrung nicht gesehen.
ALTER TABLE app_user ADD COLUMN last_seen_at TIMESTAMPTZ;
