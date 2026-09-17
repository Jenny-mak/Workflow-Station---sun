-- Dashboard assignments to request/sub tables exposed in User Portal Data -> Views.
-- Idempotent so it can upgrade an existing database whose 15-bi-management-schema.sql already ran.
CREATE TABLE IF NOT EXISTS bi_data_view_assignment (
    id                  VARCHAR(64) PRIMARY KEY,
    dashboard_id        VARCHAR(64) NOT NULL REFERENCES bi_dashboard_registry(id),
    function_unit_id    BIGINT      NOT NULL REFERENCES dw_function_units(id) ON DELETE CASCADE,
    table_id            BIGINT      NOT NULL REFERENCES dw_table_definitions(id) ON DELETE CASCADE,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(64),
    updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(64),
    CONSTRAINT uk_bi_data_view_assignment UNIQUE (dashboard_id, table_id)
);

CREATE INDEX IF NOT EXISTS idx_bi_data_view_assignment_fu
    ON bi_data_view_assignment(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_bi_data_view_assignment_table
    ON bi_data_view_assignment(table_id);
CREATE INDEX IF NOT EXISTS idx_bi_data_view_assignment_dashboard
    ON bi_data_view_assignment(dashboard_id);

COMMENT ON TABLE bi_data_view_assignment IS
    'Dashboard assignments to request/sub tables shown in User Portal Data Views';
