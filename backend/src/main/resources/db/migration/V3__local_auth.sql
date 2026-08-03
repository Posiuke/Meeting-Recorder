-- Lokale Passwort-Anmeldung (zusaetzlich zu optionalem LDAP/AD).
-- password_hash: bcrypt-Hash (nur fuer lokale Konten, z.B. den Admin). NULL = reines LDAP-Konto.
-- must_change_password: erzwingt beim naechsten Login eine Passwort-Aenderung (Initialpasswort).
ALTER TABLE app_user
    ADD COLUMN password_hash        TEXT,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
