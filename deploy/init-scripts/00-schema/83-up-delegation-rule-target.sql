-- Standing delegation rule target: USER (delegate_id) or paired BU+Role (codes).
-- Idempotent. Does not change Flowable ACT_RU_TASK.ASSIGNEE_.

ALTER TABLE IF EXISTS up_delegation_rule
    ADD COLUMN IF NOT EXISTS delegate_target_type VARCHAR(20);

ALTER TABLE IF EXISTS up_delegation_rule
    ADD COLUMN IF NOT EXISTS delegate_bu_code VARCHAR(64);

ALTER TABLE IF EXISTS up_delegation_rule
    ADD COLUMN IF NOT EXISTS delegate_role_code VARCHAR(64);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'up_delegation_rule'
          AND column_name = 'delegate_id'
          AND is_nullable = 'NO'
    ) THEN
        ALTER TABLE up_delegation_rule ALTER COLUMN delegate_id DROP NOT NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_up_delegation_rule_bu_role
    ON up_delegation_rule (delegate_bu_code, delegate_role_code);

COMMENT ON COLUMN up_delegation_rule.delegate_target_type IS
    'Delegate target: USER or BU_ROLE; null on legacy USER rows';
COMMENT ON COLUMN up_delegation_rule.delegate_bu_code IS
    'BU code when delegate_target_type is BU_ROLE';
COMMENT ON COLUMN up_delegation_rule.delegate_role_code IS
    'Role code when delegate_target_type is BU_ROLE';
