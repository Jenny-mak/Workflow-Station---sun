-- =============================================================================
-- 82: bi_dashboard_registry.superset_role_ids
-- Superset dashboard-level RBAC (dashboard_roles) synced into the local registry.
-- Sorted CSV of Superset role IDs; NULL/blank = dashboard carries no role
-- restriction. Consumed by BiDashboardAssignmentService: an assigned dashboard
-- that carries roles is visible on the embed path only to users whose RBAC
-- Mapping resolves to one of those roles (or to Superset's Admin role).
-- =============================================================================

ALTER TABLE bi_dashboard_registry ADD COLUMN IF NOT EXISTS superset_role_ids TEXT;

COMMENT ON COLUMN bi_dashboard_registry.superset_role_ids IS
    'Sorted CSV of Superset role IDs from superset.dashboard_roles; NULL = unrestricted';
