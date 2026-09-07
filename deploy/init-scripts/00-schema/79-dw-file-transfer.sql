-- Per-file transfer metadata for Upload fields (description now; FileNet archive later).
-- Idempotent: safe to re-run on an existing database.
-- NOTE for existing environments: init-scripts only run on FIRST container start.
-- Apply manually:
--   docker exec -i platform-postgres-dev psql -U <user> -d <db> \
--     -f /docker-entrypoint-initdb.d/00-schema/79-dw-file-transfer.sql

CREATE TABLE IF NOT EXISTS dw_uploaded_file_transfers (
    id BIGSERIAL PRIMARY KEY,
    stored_name VARCHAR(150) NOT NULL UNIQUE
        REFERENCES dw_uploaded_files(stored_name) ON DELETE CASCADE,
    file_description VARCHAR(500),
    callback_url TEXT,
    send_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    batch_id VARCHAR(64),
    archivable_at TIMESTAMP,
    content_purged_at TIMESTAMP,
    connection_uid VARCHAR(64),
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    lock_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_dw_file_transfer_dispatch
    ON dw_uploaded_file_transfers(send_status, archivable_at);

COMMENT ON TABLE dw_uploaded_file_transfers IS
    'Upload-field transfer row: description is written in MVP; remaining columns reserve FileNet archive.';
COMMENT ON COLUMN dw_uploaded_file_transfers.file_description IS
    'User-entered file description on Portal / Form Preview.';
COMMENT ON COLUMN dw_uploaded_file_transfers.callback_url IS
    'Reserved: FileNet callback URL after archive succeeds.';
COMMENT ON COLUMN dw_uploaded_file_transfers.send_status IS
    'Reserved archive state: PENDING / IN_PROCESS / COMPLETED / FAILED.';
COMMENT ON COLUMN dw_uploaded_file_transfers.archivable_at IS
    'Reserved: set when the process completes; NULL rows are never archived.';
COMMENT ON COLUMN dw_uploaded_file_transfers.content_purged_at IS
    'Reserved: set after local BYTEA cleanup.';
