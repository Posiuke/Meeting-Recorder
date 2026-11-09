-- Anmeldung ueber OpenID Connect (Microsoft Entra ID, Google, Keycloak, ...).
--
-- Jeder Anbieter ist eine Zeile; der Admin pflegt sie in der Oberflaeche.
-- slug steckt in der Redirect-URI (/api/auth/oidc/{slug}/callback) und darf
-- sich deshalb nach der Einrichtung beim Anbieter nicht mehr aendern.
CREATE TABLE oidc_provider (
    id                 UUID PRIMARY KEY,
    slug               VARCHAR(40)  NOT NULL UNIQUE,
    display_name       VARCHAR(100) NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Issuer; die Endpunkte kommen aus {issuer}/.well-known/openid-configuration
    issuer_uri         VARCHAR(500) NOT NULL,
    client_id          VARCHAR(255) NOT NULL,
    client_secret      VARCHAR(1000),
    scopes             VARCHAR(255) NOT NULL DEFAULT 'openid profile email',
    -- Claim fuer den Benutzernamen im Recorder (Entra: preferred_username = UPN)
    username_claim     VARCHAR(100) NOT NULL DEFAULT 'preferred_username',
    -- Claim mit Gruppen/Rollen (Entra: roles fuer App-Rollen, groups fuer Gruppen-IDs)
    groups_claim       VARCHAR(100),
    -- Kommagetrennt: Wer einen dieser Werte hat, ist Admin. Leer = Admin-Recht
    -- wird nur im Recorder vergeben.
    admin_values       VARCHAR(1000),
    -- Kommagetrennt: Nur wer einen dieser Werte hat, darf sich anmelden. Leer = alle.
    allowed_values     VARCHAR(1000),
    created_at         TIMESTAMP    NOT NULL
);

-- Herkunft eines Kontos: NULL = lokal/LDAP, sonst slug des OIDC-Anbieters plus
-- dessen unveraenderliche Kennung (sub). Ein Konto haengt an genau einem Anbieter.
ALTER TABLE app_user ADD COLUMN auth_provider VARCHAR(40);
ALTER TABLE app_user ADD COLUMN external_subject VARCHAR(255);
CREATE UNIQUE INDEX ux_app_user_external ON app_user (auth_provider, external_subject);
