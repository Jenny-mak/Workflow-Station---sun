-- Function unit Requirements / Design documents: show major.minor versions (v1.1, v1.2 …).
-- A major is one round of design: it only advances when the user starts a new AI design in
-- AI Studio (dw_ai_studio_thread_states.document_major). dw_ai_documents.version stays the
-- internal sequence used by the API paths and the optimistic-locking check.
-- Existing rows are backfilled as the first round: major 1, minor = version.
-- Idempotent: safe to re-run.
-- NOTE for existing environments: init-scripts only run on FIRST container start.
-- Apply manually:
--   docker exec -i platform-postgres-dev psql -U <user> -d <db> \
--     -f /docker-entrypoint-initdb.d/00-schema/85-dw-ai-document-major-minor.sql

ALTER TABLE dw_ai_documents ADD COLUMN IF NOT EXISTS major_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE dw_ai_documents ADD COLUMN IF NOT EXISTS minor_version INTEGER;

UPDATE dw_ai_documents SET minor_version = version WHERE minor_version IS NULL;

ALTER TABLE dw_ai_documents ALTER COLUMN minor_version SET NOT NULL;
ALTER TABLE dw_ai_documents ALTER COLUMN minor_version SET DEFAULT 1;

COMMENT ON COLUMN dw_ai_documents.major_version IS 'Design round; advances only when a new AI design is started';
COMMENT ON COLUMN dw_ai_documents.minor_version IS 'Version within the design round, starting at 1 (shown as v<major>.<minor>)';

ALTER TABLE dw_ai_studio_thread_states ADD COLUMN IF NOT EXISTS document_major INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN dw_ai_studio_thread_states.document_major IS
    'Current design round for this function unit''s documents; +1 when a new AI design is started';
