-- AI Studio copilot proposal jobs: status and result survive a DW restart.
-- Jobs still PENDING/RUNNING when DW starts are marked FAILED / AI_STUDIO_PROPOSAL_INTERRUPTED:
-- the model call cannot be resumed because the user's gateway token is never stored.
-- Terminal rows are purged after ai-generation.studio.proposal-db-retention-hours (default 24).
-- Idempotent: safe to re-run.
-- NOTE for existing environments: init-scripts only run on FIRST container start.
-- Apply manually:
--   docker exec -i platform-postgres-dev psql -U <user> -d <db> \
--     -f /docker-entrypoint-initdb.d/00-schema/84-dw-ai-studio-proposal-jobs.sql

CREATE TABLE IF NOT EXISTS dw_ai_studio_proposal_jobs (
    job_id VARCHAR(36) PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    phase VARCHAR(30) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    author_name VARCHAR(100),
    request_key TEXT,
    message TEXT,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(100),
    error_message TEXT,
    reply TEXT,
    proposal JSONB,
    proposal_scope VARCHAR(40),
    preview JSONB,
    submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    CONSTRAINT fk_ai_studio_job_function_unit FOREIGN KEY (function_unit_id)
        REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_studio_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_dw_ai_studio_jobs_owner
    ON dw_ai_studio_proposal_jobs(function_unit_id, user_id, submitted_at);
CREATE INDEX IF NOT EXISTS idx_dw_ai_studio_jobs_status
    ON dw_ai_studio_proposal_jobs(status, finished_at);

COMMENT ON TABLE dw_ai_studio_proposal_jobs IS
    'AI Studio copilot proposal jobs (status + result); no credentials are stored';
