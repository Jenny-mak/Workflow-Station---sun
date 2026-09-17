-- AI Studio shared state per function unit: confirmed phases + copilot thread messages.
-- Threads are shared by everyone who can open the function unit (same dev group); the phase a
-- user currently sits on stays in that user's browser. Undo tokens are never stored here.
-- Not reusing dw_ai_sessions / dw_ai_messages: their phase CHECK is the 3-phase AI Generate enum.
-- Idempotent: safe to re-run.
-- NOTE for existing environments: init-scripts only run on FIRST container start.
-- Apply manually:
--   docker exec -i platform-postgres-dev psql -U <user> -d <db> \
--     -f /docker-entrypoint-initdb.d/00-schema/83-dw-ai-studio-shared-thread.sql

CREATE TABLE IF NOT EXISTS dw_ai_studio_thread_states (
    function_unit_id BIGINT PRIMARY KEY,
    completed_phases JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_studio_state_function_unit FOREIGN KEY (function_unit_id)
        REFERENCES dw_function_units(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS dw_ai_studio_messages (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    phase VARCHAR(30) NOT NULL,
    role VARCHAR(10) NOT NULL,
    content TEXT NOT NULL,
    proposal JSONB,
    applied_by VARCHAR(64),
    applied_by_name VARCHAR(100),
    applied_at TIMESTAMP,
    author_user_id VARCHAR(64),
    author_name VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_studio_message_function_unit FOREIGN KEY (function_unit_id)
        REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_studio_message_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX IF NOT EXISTS idx_dw_ai_studio_messages_thread
    ON dw_ai_studio_messages(function_unit_id, phase, id);

COMMENT ON TABLE dw_ai_studio_thread_states IS
    'AI Studio progress shared per function unit (confirmed phases)';
COMMENT ON TABLE dw_ai_studio_messages IS
    'AI Studio copilot thread messages shared per function unit and phase (latest 50 kept per phase)';
COMMENT ON COLUMN dw_ai_studio_messages.proposal IS
    'Structured change proposal {scope, data, preview}; applied state lives in applied_* columns';
