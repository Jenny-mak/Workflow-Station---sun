-- Rename email connection ciphertext column: password_encrypted → credential_encrypted.
-- Ciphertext is unchanged (AES via ENCRYPTION_SECRET_KEY); this is a column identifier only.
-- Also ADD the column when an existing database never received 45/46's password_encrypted.
-- Idempotent: safe to re-run on an existing database.
-- NOTE for existing environments: init-scripts only run on FIRST container start.
-- Apply manually:
--   docker exec -i platform-postgres-dev psql -U <user> -d <db> \
--     -f /docker-entrypoint-initdb.d/00-schema/80-rename-email-connection-credential.sql

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'dw_email_connections'
          AND column_name = 'password_encrypted'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'dw_email_connections'
          AND column_name = 'credential_encrypted'
    ) THEN
        ALTER TABLE dw_email_connections RENAME COLUMN password_encrypted TO credential_encrypted;
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'sys_email_connections'
          AND column_name = 'password_encrypted'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'sys_email_connections'
          AND column_name = 'credential_encrypted'
    ) THEN
        ALTER TABLE sys_email_connections RENAME COLUMN password_encrypted TO credential_encrypted;
    END IF;
END
$$;

ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS credential_encrypted TEXT;
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS credential_encrypted TEXT;

COMMENT ON COLUMN dw_email_connections.credential_encrypted IS
    'AES ciphertext of SMTP/IMAP auth secret (ENCRYPTION_SECRET_KEY)';
COMMENT ON COLUMN sys_email_connections.credential_encrypted IS
    'AES ciphertext of SMTP/IMAP auth secret synced from developer-workstation';
