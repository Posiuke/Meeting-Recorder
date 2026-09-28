-- Bot-Vorlagen teilen: Der Besitzer gibt eine Vorlage an Nutzer oder Gruppen
-- frei. Empfaenger sehen die Vorlage samt Zeitplan, koennen daraus einen Bot
-- starten und die laufenden Bots der Vorlage steuern (verlaengern, Aufnahme
-- starten/beenden, stoppen). Bearbeiten, Loeschen und Weiterteilen bleibt beim
-- Besitzer. Neue Aufnahmen eines Bots aus der Vorlage werden automatisch an
-- dieselben Empfaenger freigegeben (share_grant).
CREATE TABLE bot_template_share (
    id               UUID PRIMARY KEY,
    bot_template_id  UUID NOT NULL REFERENCES bot_template(id) ON DELETE CASCADE,
    grantee_user_id  UUID REFERENCES app_user(id) ON DELETE CASCADE,
    grantee_group_id UUID REFERENCES user_group(id) ON DELETE CASCADE,
    created_by       UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL,
    CHECK ((grantee_user_id IS NULL) <> (grantee_group_id IS NULL))
);
CREATE INDEX idx_bot_template_share_template ON bot_template_share(bot_template_id);
CREATE INDEX idx_bot_template_share_user ON bot_template_share(grantee_user_id);
CREATE INDEX idx_bot_template_share_group ON bot_template_share(grantee_group_id);
-- Doppelte Freigaben verhindern (auch bei parallelen Anfragen)
CREATE UNIQUE INDEX uq_bot_template_share_user
    ON bot_template_share(bot_template_id, grantee_user_id) WHERE grantee_user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_bot_template_share_group
    ON bot_template_share(bot_template_id, grantee_group_id) WHERE grantee_group_id IS NOT NULL;
