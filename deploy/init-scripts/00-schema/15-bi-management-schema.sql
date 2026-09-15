-- =====================================================
-- BI Management Module: Database Tables
-- Tables with bi_* prefix for BI management features
-- Source: backend/admin-center V201 migration
-- =====================================================

-- =====================================================
-- 1. Dashboard Registry (bi_dashboard_registry)
-- Stores locally synced Dashboard metadata from Superset
-- =====================================================
CREATE TABLE IF NOT EXISTS bi_dashboard_registry (
    id                      VARCHAR(64)   PRIMARY KEY,
    dashboard_title         VARCHAR(500)  NOT NULL,
    description             TEXT,
    embed_id                UUID          NOT NULL,
    superset_dashboard_uuid UUID          NOT NULL UNIQUE,
    superset_dashboard_id   INTEGER       NOT NULL UNIQUE,
    tags                    VARCHAR(500),
    is_default_landing      BOOLEAN       NOT NULL DEFAULT FALSE,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    last_synced_at          TIMESTAMP     NOT NULL,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(64),
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_bi_dashboard_status ON bi_dashboard_registry(status);
CREATE INDEX IF NOT EXISTS idx_bi_dashboard_superset_id ON bi_dashboard_registry(superset_dashboard_id);

COMMENT ON TABLE bi_dashboard_registry IS 'Dashboard local registry synced from Superset';
COMMENT ON COLUMN bi_dashboard_registry.status IS 'ACTIVE / AUTO_INACTIVE / MANUAL_INACTIVE';
COMMENT ON COLUMN bi_dashboard_registry.tags IS 'Comma-separated local tags';
COMMENT ON COLUMN bi_dashboard_registry.embed_id IS 'UUID from Superset embedded_dashboards table, used by Embedded SDK';

-- =====================================================
-- 2. Dashboard Assignment (bi_dashboard_assignment)
-- Stores Dashboard assignment records by User/Role/BU
-- =====================================================
CREATE TABLE IF NOT EXISTS bi_dashboard_assignment (
    id              VARCHAR(64)   PRIMARY KEY,
    dashboard_id    VARCHAR(64)   NOT NULL REFERENCES bi_dashboard_registry(id),
    target_type     VARCHAR(20)   NOT NULL,
    target_id       VARCHAR(64)   NOT NULL,
    layout_mode     VARCHAR(20)   NOT NULL DEFAULT 'SINGLE',
    display_order   INTEGER       NOT NULL DEFAULT 0,
    is_default      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64),
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64),
    UNIQUE(dashboard_id, target_type, target_id)
);

CREATE INDEX IF NOT EXISTS idx_bi_assignment_target ON bi_dashboard_assignment(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_bi_assignment_dashboard ON bi_dashboard_assignment(dashboard_id);

COMMENT ON TABLE bi_dashboard_assignment IS 'Dashboard assignment records per User/Role/Business Unit';
COMMENT ON COLUMN bi_dashboard_assignment.target_type IS 'USER / ROLE / BUSINESS_UNIT';
COMMENT ON COLUMN bi_dashboard_assignment.layout_mode IS 'SINGLE / MULTI / WIDGET';

-- =====================================================
-- 2b. Data View Assignment (bi_data_view_assignment)
-- Binds dashboards to request/sub tables exposed under User Portal Data -> Views
-- =====================================================
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

-- =====================================================
-- 3. Superset Role (bi_superset_role)
-- Locally synced Superset roles from ab_role table
-- =====================================================
CREATE TABLE IF NOT EXISTS bi_superset_role (
    id                  SERIAL        PRIMARY KEY,
    superset_role_id    INTEGER       NOT NULL UNIQUE,
    name                VARCHAR(64)   NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    last_synced_at      TIMESTAMP     NOT NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE bi_superset_role IS 'Superset roles synced from ab_role table';
COMMENT ON COLUMN bi_superset_role.status IS 'ACTIVE / INACTIVE';

-- =====================================================
-- 4. RBAC Mapping (bi_rbac_mapping)
-- Maps Sys_Role to Superset_Role (many-to-many)
-- =====================================================
CREATE TABLE IF NOT EXISTS bi_rbac_mapping (
    id                  VARCHAR(64)   PRIMARY KEY,
    sys_role_id         VARCHAR(64)   NOT NULL,
    superset_role_id    INTEGER       NOT NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(64),
    UNIQUE(sys_role_id, superset_role_id),
    FOREIGN KEY (sys_role_id) REFERENCES sys_roles(id),
    FOREIGN KEY (superset_role_id) REFERENCES bi_superset_role(superset_role_id)
);

CREATE INDEX IF NOT EXISTS idx_bi_rbac_sys_role ON bi_rbac_mapping(sys_role_id);

COMMENT ON TABLE bi_rbac_mapping IS 'Mapping between system roles and Superset roles';
