-- =============================================================================
-- ALL-IN-ONE platform DDL (pure SQL for GUI clients: pgAdmin, DBeaver, etc.)
-- No psql meta-commands (no \i, \echo).
-- Order matches deploy/init-scripts/init-database.ps1 schema steps.
-- Run on empty database after CREATE DATABASE / user grants.
-- =============================================================================

SET client_min_messages = WARNING;
SET timezone = 'UTC';


-- =============================================================================
-- 01-platform-security-schema.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\01-platform-security-schema.sql
-- =============================================================================
-- =====================================================
-- Platform Security Schema - Core System Tables
-- All sys_* tables for authentication and authorization
-- Consolidated from backend/platform-security migration V100
--
-- TIMESTAMP CONVENTION:
-- Most tables use TIMESTAMP (without time zone). A few tables
-- (sys_business_units, sys_function_units, sys_virtual_group_task_history)
-- use TIMESTAMP(6) WITH TIME ZONE. The application layer should
-- always store and query timestamps in UTC to avoid conversion issues.
-- =====================================================

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =====================================================
-- 1. Users Table (sys_users)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_users (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(50),
    full_name VARCHAR(100),
    phone VARCHAR(50),
    employee_id VARCHAR(50),
    position VARCHAR(100),
    entity_manager_id VARCHAR(64),
    function_manager_id VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    language VARCHAR(10) DEFAULT 'zh_CN',
    must_change_password BOOLEAN DEFAULT false,
    password_expired_at TIMESTAMP,
    last_login_at TIMESTAMP,
    last_login_ip VARCHAR(50),
    failed_login_count INTEGER DEFAULT 0,
    locked_until TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(64),
    lock_version BIGINT DEFAULT 0,
    CONSTRAINT chk_sys_user_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DISABLED', 'LOCKED', 'PENDING'))
);

CREATE INDEX IF NOT EXISTS idx_sys_users_username ON sys_users(username);
CREATE INDEX IF NOT EXISTS idx_sys_users_email ON sys_users(email);
CREATE INDEX IF NOT EXISTS idx_sys_users_status ON sys_users(status);
CREATE INDEX IF NOT EXISTS idx_sys_users_employee_id ON sys_users(employee_id);
CREATE INDEX IF NOT EXISTS idx_sys_users_deleted ON sys_users(deleted);

-- =====================================================
-- 2. Roles Table (sys_roles)
-- Role types:
--   ADMIN: Admin roles for Admin Center management
--   DEVELOPER: Developer roles for Developer Workstation
--   BU_BOUNDED: Business unit bound roles
--   BU_UNBOUNDED: Business unit independent roles
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_roles (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'BU_UNBOUNDED',
    display_name TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    is_system BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_role_type CHECK (type IN ('ADMIN', 'DEVELOPER', 'BU_BOUNDED', 'BU_UNBOUNDED'))
);

CREATE INDEX IF NOT EXISTS idx_sys_roles_code ON sys_roles(code);
CREATE INDEX IF NOT EXISTS idx_sys_roles_type ON sys_roles(type);

COMMENT ON COLUMN sys_roles.lock_version IS 'Optimistic locking version';

-- =====================================================
-- 3. Business Units Table (sys_business_units)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_business_units (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    parent_id VARCHAR(64),
    level INTEGER NOT NULL,
    path VARCHAR(500),
    sort_order INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    description TEXT,
    cost_center VARCHAR(50),
    location VARCHAR(200),
    phone VARCHAR(50),
    created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    updated_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    CONSTRAINT chk_business_unit_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX IF NOT EXISTS idx_business_units_parent_id ON sys_business_units(parent_id);
CREATE INDEX IF NOT EXISTS idx_business_units_code ON sys_business_units(code);
CREATE INDEX IF NOT EXISTS idx_business_units_status ON sys_business_units(status);

-- =====================================================
-- 4. User-Role Association Table (sys_user_roles)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_user_roles (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assigned_by VARCHAR(64),
    valid_from TIMESTAMP,
    valid_to TIMESTAMP,
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_roles(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_user_roles_user ON sys_user_roles(user_id);
CREATE INDEX IF NOT EXISTS idx_sys_user_roles_role ON sys_user_roles(role_id);

-- =====================================================
-- 5. Role Assignments Table (sys_role_assignments)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_role_assignments (
    id VARCHAR(64) PRIMARY KEY,
    role_id VARCHAR(64) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assigned_by VARCHAR(64),
    valid_from TIMESTAMP,
    valid_to TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_target UNIQUE (role_id, target_type, target_id),
    CONSTRAINT fk_role_assignment_role FOREIGN KEY (role_id) REFERENCES sys_roles(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_role_assignments_role ON sys_role_assignments(role_id);
CREATE INDEX IF NOT EXISTS idx_role_assignments_target ON sys_role_assignments(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_role_assignments_valid ON sys_role_assignments(valid_from, valid_to);

-- =====================================================
-- 6. Permissions Table (sys_permissions)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_permissions (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50),
    resource VARCHAR(100),
    action VARCHAR(50),
    display_name TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    parent_id VARCHAR(64),
    sort_order INTEGER
);

CREATE INDEX IF NOT EXISTS idx_sys_permissions_parent ON sys_permissions(parent_id);

-- =====================================================
-- 7. Role-Permission Association Table (sys_role_permissions)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_role_permissions (
    id VARCHAR(64) PRIMARY KEY,
    role_id VARCHAR(64) NOT NULL,
    permission_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    condition_type VARCHAR(50),
    condition_value JSONB,
    granted_at TIMESTAMP(6) WITH TIME ZONE,
    granted_by VARCHAR(64),
    CONSTRAINT fk_role_perm_role FOREIGN KEY (role_id) REFERENCES sys_roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_perm_perm FOREIGN KEY (permission_id) REFERENCES sys_permissions(id) ON DELETE CASCADE,
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
);

-- =====================================================
-- 8. Login Audit Table (sys_login_audit)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_login_audit (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(64),
    username VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    login_platform VARCHAR(32),
    success BOOLEAN DEFAULT true,
    failure_reason VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_login_audit_user ON sys_login_audit(user_id);
CREATE INDEX IF NOT EXISTS idx_login_audit_username ON sys_login_audit(username);
CREATE INDEX IF NOT EXISTS idx_login_audit_created ON sys_login_audit(created_at);

-- Ensure login_platform column exists on already-created tables (CREATE TABLE IF NOT EXISTS skips existing)
ALTER TABLE sys_login_audit ADD COLUMN IF NOT EXISTS login_platform VARCHAR(32);

-- =====================================================
-- 9. Virtual Groups (sys_virtual_groups)
-- type: SYSTEM (cannot be deleted), CUSTOM (user-created), DEVELOPER (DW team)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_virtual_groups (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    display_name TEXT,
    type VARCHAR(50) DEFAULT 'CUSTOM',
    rule_expression TEXT,
    ad_group VARCHAR(100),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    CONSTRAINT chk_virtual_group_type CHECK (type IN ('SYSTEM', 'CUSTOM', 'DEVELOPER'))
);

-- =====================================================
-- 10. Virtual Group Members (sys_virtual_group_members)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_virtual_group_members (
    id VARCHAR(64) PRIMARY KEY,
    group_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    added_by VARCHAR(64),
    CONSTRAINT fk_vg_member_group FOREIGN KEY (group_id) REFERENCES sys_virtual_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_vg_member_user FOREIGN KEY (user_id) REFERENCES sys_users(id) ON DELETE CASCADE,
    CONSTRAINT uk_vg_member UNIQUE (group_id, user_id)
);

-- =====================================================
-- 11. Virtual Group Roles (sys_virtual_group_roles)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_virtual_group_roles (
    id VARCHAR(64) PRIMARY KEY,
    virtual_group_id VARCHAR(64) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_vgr_virtual_group FOREIGN KEY (virtual_group_id) REFERENCES sys_virtual_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_vgr_role FOREIGN KEY (role_id) REFERENCES sys_roles(id) ON DELETE CASCADE,
    CONSTRAINT uk_virtual_group_role UNIQUE (virtual_group_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_vgr_virtual_group_id ON sys_virtual_group_roles(virtual_group_id);
CREATE INDEX IF NOT EXISTS idx_vgr_role_id ON sys_virtual_group_roles(role_id);

-- =====================================================
-- 12. Virtual Group Task History (sys_virtual_group_task_history)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_virtual_group_task_history (
    id VARCHAR(64) PRIMARY KEY,
    group_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    from_user_id VARCHAR(64),
    to_user_id VARCHAR(64),
    assigned_user_id VARCHAR(64),
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    status VARCHAR(20),
    reason TEXT,
    comment TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vg_task_group FOREIGN KEY (group_id) REFERENCES sys_virtual_groups(id),
    CONSTRAINT chk_vg_task_action_type CHECK (action_type IN ('CREATED', 'ASSIGNED', 'CLAIMED', 'DELEGATED', 'COMPLETED', 'CANCELLED', 'RETURNED'))
);

CREATE INDEX IF NOT EXISTS idx_vg_task_history_task ON sys_virtual_group_task_history(task_id);
CREATE INDEX IF NOT EXISTS idx_vg_task_history_group ON sys_virtual_group_task_history(group_id);
CREATE INDEX IF NOT EXISTS idx_vg_task_history_action ON sys_virtual_group_task_history(action_type);
CREATE INDEX IF NOT EXISTS idx_vg_task_history_created ON sys_virtual_group_task_history(created_at);

-- =====================================================
-- 13. Business Unit Roles (sys_business_unit_roles)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_business_unit_roles (
    id VARCHAR(64) PRIMARY KEY,
    business_unit_id VARCHAR(64) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_bur_business_unit FOREIGN KEY (business_unit_id) REFERENCES sys_business_units(id) ON DELETE CASCADE,
    CONSTRAINT fk_bur_role FOREIGN KEY (role_id) REFERENCES sys_roles(id) ON DELETE CASCADE,
    CONSTRAINT uk_business_unit_role UNIQUE (business_unit_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_bur_business_unit_id ON sys_business_unit_roles(business_unit_id);
CREATE INDEX IF NOT EXISTS idx_bur_role_id ON sys_business_unit_roles(role_id);

-- =====================================================
-- 14. User Business Units (sys_user_business_units)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_user_business_units (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    business_unit_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_ubu_user FOREIGN KEY (user_id) REFERENCES sys_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ubu_business_unit FOREIGN KEY (business_unit_id) REFERENCES sys_business_units(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_business_unit UNIQUE (user_id, business_unit_id)
);

CREATE INDEX IF NOT EXISTS idx_ubu_user_id ON sys_user_business_units(user_id);
CREATE INDEX IF NOT EXISTS idx_ubu_business_unit_id ON sys_user_business_units(business_unit_id);

-- =====================================================
-- 15. User Business Unit Roles (sys_user_business_unit_roles)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_user_business_unit_roles (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    business_unit_id VARCHAR(64) NOT NULL,
    role_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_ubur_user FOREIGN KEY (user_id) REFERENCES sys_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ubur_business_unit FOREIGN KEY (business_unit_id) REFERENCES sys_business_units(id) ON DELETE CASCADE,
    CONSTRAINT fk_ubur_role FOREIGN KEY (role_id) REFERENCES sys_roles(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_bu_role UNIQUE (user_id, business_unit_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_ubur_user_id ON sys_user_business_unit_roles(user_id);
CREATE INDEX IF NOT EXISTS idx_ubur_business_unit_id ON sys_user_business_unit_roles(business_unit_id);
CREATE INDEX IF NOT EXISTS idx_ubur_role_id ON sys_user_business_unit_roles(role_id);

-- =====================================================
-- 16. Approvers (sys_approvers)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_approvers (
    id VARCHAR(64) PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_approver_user FOREIGN KEY (user_id) REFERENCES sys_users(id) ON DELETE CASCADE,
    CONSTRAINT uk_approver UNIQUE (target_type, target_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_approver_target ON sys_approvers(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_approver_user_id ON sys_approvers(user_id);

-- =====================================================
-- 17. Permission Requests (sys_permission_requests)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_permission_requests (
    id VARCHAR(64) PRIMARY KEY,
    applicant_id VARCHAR(64) NOT NULL,
    request_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    role_ids TEXT,
    reason TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approver_id VARCHAR(64),
    approver_comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    approved_at TIMESTAMP,
    CONSTRAINT fk_pr_applicant FOREIGN KEY (applicant_id) REFERENCES sys_users(id),
    CONSTRAINT chk_pr_request_type CHECK (request_type IN ('VIRTUAL_GROUP', 'BUSINESS_UNIT', 'BUSINESS_UNIT_ROLE')),
    CONSTRAINT chk_pr_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_pr_applicant_id ON sys_permission_requests(applicant_id);
CREATE INDEX IF NOT EXISTS idx_pr_status ON sys_permission_requests(status);
CREATE INDEX IF NOT EXISTS idx_pr_request_type ON sys_permission_requests(request_type);
CREATE INDEX IF NOT EXISTS idx_pr_target_id ON sys_permission_requests(target_id);

-- =====================================================
-- 18. Member Change Logs (sys_member_change_logs)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_member_change_logs (
    id VARCHAR(64) PRIMARY KEY,
    change_type VARCHAR(20) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    role_ids TEXT,
    operator_id VARCHAR(64),
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mcl_target ON sys_member_change_logs(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_mcl_user_id ON sys_member_change_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_mcl_change_type ON sys_member_change_logs(change_type);
CREATE INDEX IF NOT EXISTS idx_mcl_created_at ON sys_member_change_logs(created_at);

-- =====================================================
-- 19. User Preferences (sys_user_preferences)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_user_preferences (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    preference_key VARCHAR(100) NOT NULL,
    preference_value VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_user_pref_user FOREIGN KEY (user_id) REFERENCES sys_users(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_preference UNIQUE (user_id, preference_key)
);

CREATE INDEX IF NOT EXISTS idx_user_preferences_user_id ON sys_user_preferences(user_id);
CREATE INDEX IF NOT EXISTS idx_user_preferences_key ON sys_user_preferences(preference_key);

-- =====================================================
-- 20. Dictionaries (sys_dictionaries)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_dictionaries (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50),
    display_name TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    cache_ttl INTEGER,
    created_by VARCHAR(64),
    data_source_config TEXT,
    data_source_type VARCHAR(20),
    sort_order INTEGER,
    updated_by VARCHAR(64),
    version INTEGER DEFAULT 0,
    CONSTRAINT chk_dict_data_source_type CHECK (data_source_type IN ('DATABASE', 'API', 'FILE', 'STATIC'))
);

CREATE INDEX IF NOT EXISTS idx_dict_code ON sys_dictionaries(code);
CREATE INDEX IF NOT EXISTS idx_dict_type ON sys_dictionaries(type);
CREATE INDEX IF NOT EXISTS idx_dict_status ON sys_dictionaries(status);

-- =====================================================
-- 21. Dictionary Items (sys_dictionary_items)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_dictionary_items (
    id VARCHAR(64) PRIMARY KEY,
    dictionary_id VARCHAR(64) NOT NULL,
    parent_id VARCHAR(64),
    item_code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    name_en VARCHAR(200),
    name_zh_cn VARCHAR(200),
    name_zh_tw VARCHAR(200),
    value VARCHAR(500),
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    sort_order INTEGER DEFAULT 0,
    valid_from TIMESTAMP,
    valid_to TIMESTAMP,
    ext_attributes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    CONSTRAINT fk_dict_item FOREIGN KEY (dictionary_id) REFERENCES sys_dictionaries(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dict_item_dict_id ON sys_dictionary_items(dictionary_id);
CREATE INDEX IF NOT EXISTS idx_dict_item_code ON sys_dictionary_items(dictionary_id, item_code);

-- =====================================================
-- 22. Dictionary Versions (sys_dictionary_versions)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_dictionary_versions (
    id VARCHAR(36) PRIMARY KEY,
    dictionary_id VARCHAR(36) NOT NULL,
    version INTEGER NOT NULL,
    snapshot_data TEXT NOT NULL,
    change_description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT uk_dict_version UNIQUE (dictionary_id, version)
);

CREATE INDEX IF NOT EXISTS idx_dict_ver_dict_id ON sys_dictionary_versions(dictionary_id);

-- =====================================================
-- 23. Dictionary Data Sources (sys_dictionary_data_sources)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_dictionary_data_sources (
    id VARCHAR(36) PRIMARY KEY,
    dictionary_id VARCHAR(36) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    connection_string VARCHAR(500),
    table_name VARCHAR(200),
    code_field VARCHAR(100),
    name_field VARCHAR(100),
    value_field VARCHAR(100),
    filter_condition VARCHAR(500),
    order_by_field VARCHAR(100),
    cache_ttl INTEGER DEFAULT 300,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dict_ds_dict_id ON sys_dictionary_data_sources(dictionary_id);

-- =====================================================
-- 24. Function Units (sys_function_units)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_function_units (
    id VARCHAR(64) PRIMARY KEY,
    checksum VARCHAR(64),
    code VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(64),
    display_name TEXT,
    digital_signature TEXT,
    enabled BOOLEAN NOT NULL,
    imported_at TIMESTAMP(6) WITH TIME ZONE,
    imported_by VARCHAR(64),
    name VARCHAR(100) NOT NULL,
    package_path VARCHAR(500),
    package_size BIGINT,
    status VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    updated_by VARCHAR(64),
    validated_at TIMESTAMP(6) WITH TIME ZONE,
    validated_by VARCHAR(64),
    version VARCHAR(20) NOT NULL,
    process_deployed BOOLEAN DEFAULT false,
    process_deployment_count INTEGER DEFAULT 0,
    icon_svg TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    deployed_at TIMESTAMP,
    previous_version_id VARCHAR(64),
    CONSTRAINT chk_func_unit_status CHECK (status IN ('DRAFT', 'VALIDATED', 'DEPLOYED', 'DEPRECATED', 'ARCHIVED')),
    CONSTRAINT fk_sys_function_unit_previous_version FOREIGN KEY (previous_version_id) REFERENCES sys_function_units(id) ON DELETE SET NULL
);

-- =====================================================
-- 25. Function Unit Deployments (sys_function_unit_deployments)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_function_unit_deployments (
    id VARCHAR(64) PRIMARY KEY,
    function_unit_id VARCHAR(64) NOT NULL,
    environment VARCHAR(20) NOT NULL,
    strategy VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    deployed_at TIMESTAMP,
    deployed_by VARCHAR(64),
    completed_at TIMESTAMP,
    rollback_to_id VARCHAR(64),
    error_message TEXT,
    deployment_log TEXT,
    started_at TIMESTAMP,
    rollback_reason TEXT,
    rollback_by VARCHAR(64),
    rollback_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_deployment_func_unit FOREIGN KEY (function_unit_id) REFERENCES sys_function_units(id)
);

CREATE INDEX IF NOT EXISTS idx_fu_deployment_func_unit ON sys_function_unit_deployments(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_fu_deployment_status ON sys_function_unit_deployments(status);

-- =====================================================
-- 26. Function Unit Approvals (sys_function_unit_approvals)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_function_unit_approvals (
    id VARCHAR(64) PRIMARY KEY,
    deployment_id VARCHAR(64) NOT NULL,
    approval_type VARCHAR(20) NOT NULL,
    approval_order INTEGER DEFAULT 1,
    approver_id VARCHAR(64) NOT NULL,
    approver_name VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    comment TEXT,
    approved_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_approval_deployment FOREIGN KEY (deployment_id) REFERENCES sys_function_unit_deployments(id)
);

CREATE INDEX IF NOT EXISTS idx_fu_approval_deployment ON sys_function_unit_approvals(deployment_id);

-- =====================================================
-- 27. Function Unit Dependencies (sys_function_unit_dependencies)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_function_unit_dependencies (
    id VARCHAR(64) PRIMARY KEY,
    function_unit_id VARCHAR(64) NOT NULL,
    dependency_code VARCHAR(50) NOT NULL,
    dependency_version VARCHAR(20) NOT NULL,
    dependency_type VARCHAR(20) NOT NULL DEFAULT 'REQUIRED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dependency_func_unit FOREIGN KEY (function_unit_id) REFERENCES sys_function_units(id)
);

CREATE INDEX IF NOT EXISTS idx_fu_dependency_func_unit ON sys_function_unit_dependencies(function_unit_id);

-- =====================================================
-- 28. Function Unit Contents (sys_function_unit_contents)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_function_unit_contents (
    id VARCHAR(64) PRIMARY KEY,
    function_unit_id VARCHAR(64) NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    content_name VARCHAR(200) NOT NULL,
    content_path VARCHAR(500),
    content_data TEXT,
    checksum VARCHAR(64),
    source_id VARCHAR(64),
    flowable_deployment_id VARCHAR(64),
    flowable_process_definition_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_content_func_unit FOREIGN KEY (function_unit_id) REFERENCES sys_function_units(id),
    CONSTRAINT chk_content_type CHECK (content_type IN ('PROCESS', 'FORM', 'DATA_TABLE', 'SCRIPT'))
);

CREATE INDEX IF NOT EXISTS idx_fu_content_func_unit ON sys_function_unit_contents(function_unit_id);

-- =====================================================
-- 29. Function Unit Access (sys_function_unit_access)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_function_unit_access (
    id VARCHAR(64) PRIMARY KEY,
    function_unit_id VARCHAR(64) NOT NULL,
    access_type VARCHAR(20) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_access_func_unit FOREIGN KEY (function_unit_id) REFERENCES sys_function_units(id)
);

CREATE INDEX IF NOT EXISTS idx_fu_access_func_unit ON sys_function_unit_access(function_unit_id);

-- =====================================================
-- 30. Developer Role Permissions (sys_developer_role_permissions)
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_developer_role_permissions (
    id VARCHAR(64) PRIMARY KEY,
    role_id VARCHAR(64) NOT NULL,
    permission VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_dev_role_perm_role FOREIGN KEY (role_id) REFERENCES sys_roles(id) ON DELETE CASCADE,
    CONSTRAINT uk_dev_role_permission UNIQUE (role_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_dev_role_perm_role ON sys_developer_role_permissions(role_id);

-- =====================================================
-- Comments
-- =====================================================
COMMENT ON TABLE sys_users IS 'Unified user table for all services';
COMMENT ON TABLE sys_roles IS 'Role definitions';
COMMENT ON TABLE sys_business_units IS 'Organization structure (business units)';
COMMENT ON TABLE sys_user_roles IS 'User-Role associations';
COMMENT ON TABLE sys_role_assignments IS 'Role assignments to users/departments/groups';
COMMENT ON TABLE sys_permissions IS 'Permission definitions';
COMMENT ON TABLE sys_role_permissions IS 'Role-Permission associations';
COMMENT ON TABLE sys_login_audit IS 'Login/logout audit trail';
COMMENT ON TABLE sys_virtual_groups IS 'Virtual groups for role assignment. type=SYSTEM|CUSTOM|DEVELOPER; SYSTEM groups cannot be deleted.';
COMMENT ON TABLE sys_virtual_group_members IS 'Virtual group member associations';
COMMENT ON TABLE sys_virtual_group_roles IS 'Virtual group role bindings';
COMMENT ON TABLE sys_virtual_group_task_history IS 'Virtual group task assignment history';
COMMENT ON TABLE sys_business_unit_roles IS 'Business unit role bindings';
COMMENT ON TABLE sys_user_business_units IS 'User business unit membership';
COMMENT ON TABLE sys_user_business_unit_roles IS 'User business unit role assignments';
COMMENT ON TABLE sys_approvers IS 'Approver configurations';
COMMENT ON TABLE sys_permission_requests IS 'Permission requests';
COMMENT ON TABLE sys_member_change_logs IS 'Member change audit logs';
COMMENT ON TABLE sys_user_preferences IS 'User preferences';
COMMENT ON TABLE sys_dictionaries IS 'Data dictionaries';
COMMENT ON TABLE sys_dictionary_items IS 'Dictionary items';
COMMENT ON TABLE sys_function_units IS 'Function unit packages';
COMMENT ON TABLE sys_developer_role_permissions IS 'Developer role permission mappings';


-- =============================================================================
-- 02-workflow-engine-schema.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\02-workflow-engine-schema.sql
-- =============================================================================
-- =====================================================
-- Workflow Engine Core Schema - Workflow Tables
-- Tables with wf_* prefix for workflow engine features
-- Consolidated from backend/workflow-engine-core migration V500
-- Note: Core sys_* tables are created by platform-security
-- =====================================================

-- Enable required extensions (if not already enabled)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =====================================================
-- 1. Extended Task Info (wf_extended_task_info)
-- Supports multi-dimensional task assignment
-- =====================================================
CREATE TABLE IF NOT EXISTS wf_extended_task_info (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL UNIQUE,
    process_instance_id VARCHAR(64) NOT NULL,
    process_definition_id VARCHAR(64) NOT NULL,
    task_definition_key VARCHAR(255),
    task_name VARCHAR(255),
    task_description VARCHAR(4000),
    assignment_type VARCHAR(20) NOT NULL,
    assignment_target VARCHAR(255) NOT NULL,
    original_assignee VARCHAR(64),
    delegated_to VARCHAR(64),
    delegated_by VARCHAR(64),
    delegated_time TIMESTAMP,
    delegation_reason VARCHAR(500),
    claimed_by VARCHAR(64),
    claimed_time TIMESTAMP,
    priority INTEGER,
    due_date TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    created_time TIMESTAMP NOT NULL,
    updated_time TIMESTAMP,
    completed_time TIMESTAMP,
    completed_by VARCHAR(64),
    form_key VARCHAR(255),
    business_key VARCHAR(255),
    extended_properties TEXT,
    tenant_id VARCHAR(64),
    version BIGINT,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    created_by VARCHAR(64),
    updated_by VARCHAR(64)
);

-- Indexes for wf_extended_task_info
CREATE INDEX IF NOT EXISTS idx_task_id ON wf_extended_task_info(task_id);
CREATE INDEX IF NOT EXISTS idx_assignment_type ON wf_extended_task_info(assignment_type);
CREATE INDEX IF NOT EXISTS idx_assignment_target ON wf_extended_task_info(assignment_target);
CREATE INDEX IF NOT EXISTS idx_delegated_to ON wf_extended_task_info(delegated_to);
CREATE INDEX IF NOT EXISTS idx_claimed_by ON wf_extended_task_info(claimed_by);
CREATE INDEX IF NOT EXISTS idx_process_instance ON wf_extended_task_info(process_instance_id);
CREATE INDEX IF NOT EXISTS idx_created_time ON wf_extended_task_info(created_time);
CREATE INDEX IF NOT EXISTS idx_due_date ON wf_extended_task_info(due_date);
CREATE INDEX IF NOT EXISTS idx_priority ON wf_extended_task_info(priority);
CREATE INDEX IF NOT EXISTS idx_status ON wf_extended_task_info(status);

-- =====================================================
-- 2. Process Variables (wf_process_variables)
-- Stores process variable history and extended info
-- =====================================================
CREATE TABLE IF NOT EXISTS wf_process_variables (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL,
    process_instance_id VARCHAR(64),
    execution_id VARCHAR(64),
    task_id VARCHAR(64),
    case_instance_id VARCHAR(64),
    case_execution_id VARCHAR(64),
    activity_instance_id VARCHAR(64),
    tenant_id VARCHAR(255),
    sequence_counter BIGINT,
    is_concurrent_local BOOLEAN DEFAULT false,
    text_value TEXT,
    text_value2 TEXT,
    double_value DOUBLE PRECISION,
    long_value BIGINT,
    date_value TIMESTAMP,
    json_value JSONB,
    binary_value BYTEA,
    created_time TIMESTAMP NOT NULL,
    updated_time TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    change_reason VARCHAR(500),
    operation_type VARCHAR(20)
);

-- Indexes for wf_process_variables
CREATE INDEX IF NOT EXISTS idx_variable_name ON wf_process_variables(name);
CREATE INDEX IF NOT EXISTS idx_variable_proc_inst ON wf_process_variables(process_instance_id);
CREATE INDEX IF NOT EXISTS idx_variable_task ON wf_process_variables(task_id);
CREATE INDEX IF NOT EXISTS idx_variable_created_time ON wf_process_variables(created_time);

-- =====================================================
-- 3. Workflow Audit Logs (wf_audit_logs)
-- Records all workflow operation audit trails
-- =====================================================
CREATE TABLE IF NOT EXISTS wf_audit_logs (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    resource_name VARCHAR(255),
    operation_description TEXT,
    before_data JSONB,
    after_data JSONB,
    operation_result VARCHAR(20) NOT NULL,
    error_message TEXT,
    timestamp TIMESTAMP NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    session_id VARCHAR(128),
    request_id VARCHAR(64),
    duration_ms BIGINT,
    tenant_id VARCHAR(64),
    context_data JSONB,
    risk_level VARCHAR(20),
    is_sensitive BOOLEAN NOT NULL DEFAULT false
);

-- Indexes for wf_audit_logs
CREATE INDEX IF NOT EXISTS idx_audit_user_id ON wf_audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_operation_type ON wf_audit_logs(operation_type);
CREATE INDEX IF NOT EXISTS idx_audit_resource_type ON wf_audit_logs(resource_type);
CREATE INDEX IF NOT EXISTS idx_audit_resource_id ON wf_audit_logs(resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON wf_audit_logs(timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_ip_address ON wf_audit_logs(ip_address);
CREATE INDEX IF NOT EXISTS idx_audit_session_id ON wf_audit_logs(session_id);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_id ON wf_audit_logs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_composite ON wf_audit_logs(user_id, operation_type, timestamp);

-- =====================================================
-- 4. Exception Records (wf_exception_records)
-- Records workflow execution exceptions
-- =====================================================
CREATE TABLE IF NOT EXISTS wf_exception_records (
    id VARCHAR(64) PRIMARY KEY,
    process_instance_id VARCHAR(64),
    process_definition_id VARCHAR(64),
    process_definition_key VARCHAR(255),
    task_id VARCHAR(64),
    task_name VARCHAR(255),
    activity_id VARCHAR(255),
    activity_name VARCHAR(255),
    exception_type VARCHAR(100) NOT NULL,
    exception_class VARCHAR(500),
    exception_message TEXT,
    stack_trace TEXT,
    root_cause TEXT,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    context_data TEXT,
    variables_snapshot TEXT,
    occurred_time TIMESTAMP NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retry_count INTEGER NOT NULL DEFAULT 3,
    next_retry_time TIMESTAMP,
    last_retry_time TIMESTAMP,
    resolved BOOLEAN NOT NULL DEFAULT false,
    resolved_time TIMESTAMP,
    resolved_by VARCHAR(64),
    resolution_method VARCHAR(50),
    resolution_note TEXT,
    alert_sent BOOLEAN NOT NULL DEFAULT false,
    alert_sent_time TIMESTAMP,
    parent_exception_id VARCHAR(64),
    tenant_id VARCHAR(64),
    created_time TIMESTAMP NOT NULL,
    updated_time TIMESTAMP,
    CONSTRAINT wf_exception_records_severity_check CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT wf_exception_records_status_check CHECK (status IN ('PENDING', 'PROCESSING', 'RESOLVED', 'IGNORED'))
);

-- Indexes for wf_exception_records
CREATE INDEX IF NOT EXISTS idx_exception_process_instance ON wf_exception_records(process_instance_id);
CREATE INDEX IF NOT EXISTS idx_exception_task_id ON wf_exception_records(task_id);
CREATE INDEX IF NOT EXISTS idx_exception_type ON wf_exception_records(exception_type);
CREATE INDEX IF NOT EXISTS idx_exception_severity ON wf_exception_records(severity);
CREATE INDEX IF NOT EXISTS idx_exception_status ON wf_exception_records(status);
CREATE INDEX IF NOT EXISTS idx_exception_occurred_time ON wf_exception_records(occurred_time);
CREATE INDEX IF NOT EXISTS idx_exception_resolved ON wf_exception_records(resolved);

-- =====================================================
-- Comments
-- =====================================================
COMMENT ON TABLE wf_extended_task_info IS 'Extended task information for workflow tasks with multi-dimensional assignment support';
COMMENT ON TABLE wf_process_variables IS 'Process instance variables with history and extended info';
COMMENT ON TABLE wf_audit_logs IS 'Workflow operation audit logs for compliance and analysis';
COMMENT ON TABLE wf_exception_records IS 'Workflow exception records with retry and resolution tracking';

COMMENT ON COLUMN wf_extended_task_info.assignment_type IS 'Assignment type: USER, VIRTUAL_GROUP, DEPT_ROLE, etc.';
COMMENT ON COLUMN wf_extended_task_info.status IS 'Task status: CREATED, ASSIGNED, CLAIMED, DELEGATED, IN_PROGRESS, COMPLETED, CANCELLED';

COMMENT ON COLUMN wf_process_variables.type IS 'Variable type: STRING, INTEGER, LONG, DOUBLE, BOOLEAN, DATE, JSON, BINARY';

COMMENT ON COLUMN wf_audit_logs.resource_type IS 'Resource type: PROCESS_DEFINITION, PROCESS_INSTANCE, TASK, VARIABLE, FORM, USER, ROLE';
COMMENT ON COLUMN wf_audit_logs.operation_result IS 'Operation result: SUCCESS, FAILURE, PARTIAL';
COMMENT ON COLUMN wf_audit_logs.risk_level IS 'Risk level: LOW, MEDIUM, HIGH, CRITICAL';
COMMENT ON COLUMN wf_audit_logs.is_sensitive IS 'Whether this is a sensitive operation';

COMMENT ON COLUMN wf_exception_records.severity IS 'Severity level: CRITICAL, HIGH, MEDIUM, LOW';
COMMENT ON COLUMN wf_exception_records.status IS 'Exception status: PENDING, PROCESSING, RESOLVED, IGNORED';
COMMENT ON COLUMN wf_exception_records.resolution_method IS 'Resolution method: AUTO_RETRY, MANUAL_FIX, IGNORED, COMPENSATED';

-- =====================================================
-- 5. Activepieces 执行记录 (wf_ap_execution_record)
-- =====================================================
CREATE TABLE IF NOT EXISTS wf_ap_execution_record (
    id BIGSERIAL PRIMARY KEY,
    process_instance_id VARCHAR(64),
    task_id VARCHAR(64),
    ap_flow_id VARCHAR(100),
    webhook_url VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    input_data JSONB,
    output_data JSONB,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    timeout_seconds INTEGER DEFAULT 120,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT wf_ap_exec_status_check CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT')),
    CONSTRAINT wf_ap_exec_source_check CHECK (source_type IN ('SERVICE_TASK', 'ACTION'))
);

CREATE INDEX IF NOT EXISTS idx_ap_exec_process_instance ON wf_ap_execution_record(process_instance_id);
CREATE INDEX IF NOT EXISTS idx_ap_exec_task_id ON wf_ap_execution_record(task_id);
CREATE INDEX IF NOT EXISTS idx_ap_exec_status ON wf_ap_execution_record(status);
CREATE INDEX IF NOT EXISTS idx_ap_exec_created_at ON wf_ap_execution_record(created_at);

COMMENT ON TABLE wf_ap_execution_record IS 'Activepieces 工作流执行记录';
COMMENT ON COLUMN wf_ap_execution_record.status IS '执行状态: PENDING, RUNNING, SUCCESS, FAILED, TIMEOUT';
COMMENT ON COLUMN wf_ap_execution_record.source_type IS '执行来源: SERVICE_TASK（任务节点触发）, ACTION（用户操作触发）';


-- =============================================================================
-- 03-user-portal-schema.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\03-user-portal-schema.sql
-- =============================================================================
-- =====================================================
-- User Portal Schema - User Portal Tables
-- Tables with up_* prefix for user portal features
-- Consolidated from backend/user-portal migration V400
-- =====================================================

-- =====================================================
-- 1. User Preferences (up_user_preference)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_user_preference (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL UNIQUE,
    theme VARCHAR(20) DEFAULT 'light',
    theme_color VARCHAR(20) DEFAULT '#DB0011',
    font_size VARCHAR(10) DEFAULT 'medium',
    layout_density VARCHAR(10) DEFAULT 'normal',
    language VARCHAR(10) DEFAULT 'zh-CN',
    timezone VARCHAR(50) DEFAULT 'Asia/Shanghai',
    date_format VARCHAR(20) DEFAULT 'YYYY-MM-DD',
    page_size INTEGER DEFAULT 20,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_up_user_preference_user_id ON up_user_preference(user_id);

-- =====================================================
-- 2. Dashboard Layout (up_dashboard_layout)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_dashboard_layout (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    component_id VARCHAR(50) NOT NULL,
    component_type VARCHAR(50) NOT NULL,
    grid_x INTEGER NOT NULL,
    grid_y INTEGER NOT NULL,
    grid_w INTEGER NOT NULL,
    grid_h INTEGER NOT NULL,
    is_visible BOOLEAN DEFAULT TRUE,
    config JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, component_id)
);

CREATE INDEX IF NOT EXISTS idx_up_dashboard_layout_user_id ON up_dashboard_layout(user_id);

-- =====================================================
-- 3. Notification Preferences (up_notification_preference)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_notification_preference (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    email_enabled BOOLEAN DEFAULT TRUE,
    browser_enabled BOOLEAN DEFAULT TRUE,
    in_app_enabled BOOLEAN DEFAULT TRUE,
    quiet_start_time TIME,
    quiet_end_time TIME,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, notification_type)
);

CREATE INDEX IF NOT EXISTS idx_up_notification_preference_user_id ON up_notification_preference(user_id);

-- =====================================================
-- 4. Delegation Rules (up_delegation_rule)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_delegation_rule (
    id BIGSERIAL PRIMARY KEY,
    delegator_id VARCHAR(64) NOT NULL,
    delegate_id VARCHAR(64) NOT NULL,
    delegation_type VARCHAR(20) NOT NULL,
    process_types JSONB,
    priority_filter JSONB,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    lock_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_up_delegation_rule_delegator_id ON up_delegation_rule(delegator_id);
CREATE INDEX IF NOT EXISTS idx_up_delegation_rule_delegate_id ON up_delegation_rule(delegate_id);
CREATE INDEX IF NOT EXISTS idx_up_delegation_rule_status ON up_delegation_rule(status);

-- =====================================================
-- 5. Permission Requests (up_permission_request)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_permission_request (
    id BIGSERIAL PRIMARY KEY,
    applicant_id VARCHAR(64) NOT NULL,
    request_type VARCHAR(30) NOT NULL,
    permissions JSONB,
    reason TEXT NOT NULL,
    valid_from TIMESTAMP,
    valid_to TIMESTAMP,
    status VARCHAR(20) DEFAULT 'PENDING',
    approver_id VARCHAR(64),
    approve_time TIMESTAMP,
    approve_comment TEXT,
    -- Role request fields
    role_id VARCHAR(64),
    role_name VARCHAR(100),
    organization_unit_id VARCHAR(64),
    organization_unit_name VARCHAR(200),
    -- Virtual group request fields
    virtual_group_id VARCHAR(64),
    virtual_group_name VARCHAR(200),
    -- Business unit request fields
    business_unit_id VARCHAR(64),
    business_unit_name VARCHAR(200),
    submitted_by_user_id VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_up_permission_request_applicant_id ON up_permission_request(applicant_id);
CREATE INDEX IF NOT EXISTS idx_up_permission_request_submitted_by ON up_permission_request(submitted_by_user_id);
CREATE INDEX IF NOT EXISTS idx_up_permission_request_status ON up_permission_request(status);

-- =====================================================
-- 6. Favorite Processes (up_favorite_process)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_favorite_process (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    process_definition_key VARCHAR(255) NOT NULL,
    display_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, process_definition_key)
);

CREATE INDEX IF NOT EXISTS idx_up_favorite_process_user_id ON up_favorite_process(user_id);

-- =====================================================
-- 7. Process Drafts (up_process_draft)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_process_draft (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    process_definition_key VARCHAR(255) NOT NULL,
    form_data JSONB NOT NULL,
    attachments JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    lock_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_up_process_draft_user_id ON up_process_draft(user_id);
CREATE INDEX IF NOT EXISTS idx_up_process_draft_process_key ON up_process_draft(process_definition_key);

-- =====================================================
-- 8. Delegation Audit (up_delegation_audit)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_delegation_audit (
    id BIGSERIAL PRIMARY KEY,
    delegator_id VARCHAR(64) NOT NULL,
    delegate_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64),
    operation_type VARCHAR(50) NOT NULL,
    operation_result VARCHAR(50),
    operation_detail TEXT,
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_up_delegation_audit_delegator_id ON up_delegation_audit(delegator_id);
CREATE INDEX IF NOT EXISTS idx_up_delegation_audit_delegate_id ON up_delegation_audit(delegate_id);
CREATE INDEX IF NOT EXISTS idx_up_delegation_audit_task_id ON up_delegation_audit(task_id);
CREATE INDEX IF NOT EXISTS idx_up_delegation_audit_created_at ON up_delegation_audit(created_at);

-- =====================================================
-- 9. Process Instances (up_process_instance)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_process_instance (
    id VARCHAR(64) PRIMARY KEY,
    process_definition_key VARCHAR(255) NOT NULL,
    process_definition_id VARCHAR(64),
    process_definition_name VARCHAR(255),
    process_instance_id VARCHAR(64),
    business_key VARCHAR(255),
    initiator_id VARCHAR(64),
    start_user_id VARCHAR(64) NOT NULL,
    start_user_name VARCHAR(100),
    title VARCHAR(200),
    status VARCHAR(20) DEFAULT 'RUNNING',
    priority VARCHAR(32),
    variables JSONB,
    variables_json TEXT,
    current_node VARCHAR(255),
    current_assignee VARCHAR(64),
    candidate_users VARCHAR(500),
    start_time TIMESTAMP(6),
    end_time TIMESTAMP(6),
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    updated_at TIMESTAMP(6),
    lock_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_up_process_instance_user ON up_process_instance(start_user_id);
CREATE INDEX IF NOT EXISTS idx_up_process_instance_status ON up_process_instance(status);
CREATE INDEX IF NOT EXISTS idx_up_process_instance_key ON up_process_instance(process_definition_key);

-- =====================================================
-- 10. Process History (up_process_history)
-- =====================================================
CREATE TABLE IF NOT EXISTS up_process_history (
    id BIGSERIAL PRIMARY KEY,
    process_instance_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64),
    activity_id VARCHAR(100),
    activity_name VARCHAR(255),
    activity_type VARCHAR(50),
    operation_type VARCHAR(50) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    operator_name VARCHAR(100),
    comment TEXT,
    duration BIGINT,
    operation_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_process_history_instance ON up_process_history(process_instance_id);

-- =====================================================
-- Comments
-- =====================================================
COMMENT ON TABLE up_user_preference IS 'User preference settings';
COMMENT ON TABLE up_dashboard_layout IS 'Dashboard layout configuration';
COMMENT ON TABLE up_notification_preference IS 'Notification preferences';
COMMENT ON TABLE up_delegation_rule IS 'Delegation rules';
COMMENT ON TABLE up_permission_request IS 'Permission requests';
COMMENT ON TABLE up_favorite_process IS 'Favorite processes';
COMMENT ON TABLE up_process_draft IS 'Process drafts';
COMMENT ON TABLE up_delegation_audit IS 'Delegation audit logs';
COMMENT ON TABLE up_process_instance IS 'Process instances';
COMMENT ON TABLE up_process_history IS 'Process operation history';


-- =============================================================================
-- 04-developer-workstation-schema.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\04-developer-workstation-schema.sql
-- =============================================================================
-- =====================================================
-- Developer Workstation Schema - Developer Tables
-- All dw_* tables for developer workstation features
-- Consolidated from backend/developer-workstation migration V300
--
-- ID CONVENTION:
-- dw_* tables use BIGSERIAL (auto-increment integer) IDs.
-- sys_* tables use VARCHAR(64) (UUID string) IDs.
-- bi_* tables use VARCHAR(64) (UUID string) IDs.
-- Cross-table ID type conversion is handled at the application
-- layer during deployment (dw → sys).
-- =====================================================

-- =====================================================
-- 1. Icons Table (dw_icons) - Must be created first for FK references
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_icons (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    category VARCHAR(30) NOT NULL,
    svg_content TEXT NOT NULL,
    file_size INTEGER,
    description VARCHAR(500),
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dw_icons_name ON dw_icons(name);
CREATE INDEX IF NOT EXISTS idx_dw_icons_category ON dw_icons(category);

-- =====================================================
-- 2. Function Units Table (dw_function_units)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_function_units (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL UNIQUE,
    display_name TEXT,
    icon_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_version VARCHAR(20),
    version VARCHAR(20) NOT NULL DEFAULT '1.0.0',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deployed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    previous_version_id BIGINT,
    lock_version BIGINT DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_function_unit_icon FOREIGN KEY (icon_id) REFERENCES dw_icons(id) ON DELETE SET NULL,
    CONSTRAINT fk_dw_function_unit_previous_version FOREIGN KEY (previous_version_id) REFERENCES dw_function_units(id) ON DELETE SET NULL,
    CONSTRAINT chk_function_unit_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_dw_function_units_name ON dw_function_units(name);
CREATE INDEX IF NOT EXISTS idx_dw_function_units_status ON dw_function_units(status);
CREATE INDEX IF NOT EXISTS idx_function_unit_code ON dw_function_units(code);
CREATE INDEX IF NOT EXISTS idx_dw_function_unit_version ON dw_function_units(name, version);
CREATE INDEX IF NOT EXISTS idx_dw_function_unit_active ON dw_function_units(name, is_active);
CREATE INDEX IF NOT EXISTS idx_dw_function_unit_deployed_at ON dw_function_units(deployed_at);

-- =====================================================
-- 3. Process Definitions Table (dw_process_definitions)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_process_definitions (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    bpmn_xml TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_process_function_unit FOREIGN KEY (function_unit_id) REFERENCES dw_function_units(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dw_process_definitions_fu ON dw_process_definitions(function_unit_id);

-- =====================================================
-- 4. Table Definitions Table (dw_table_definitions)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_table_definitions (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    table_display_name VARCHAR(200),
    table_type VARCHAR(20) NOT NULL,
    display_name TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_table_function_unit FOREIGN KEY (function_unit_id) REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT uk_table_name_fu UNIQUE (function_unit_id, table_name),
    CONSTRAINT chk_table_type CHECK (table_type IN ('MAIN', 'SUB', 'ACTION', 'RELATION'))
);

CREATE INDEX IF NOT EXISTS idx_dw_table_definitions_fu ON dw_table_definitions(function_unit_id);

-- =====================================================
-- 5. Field Definitions Table (dw_field_definitions)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_field_definitions (
    id BIGSERIAL PRIMARY KEY,
    table_id BIGINT NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    data_type VARCHAR(50) NOT NULL,
    length INTEGER,
    precision_value INTEGER,
    scale INTEGER,
    nullable BOOLEAN DEFAULT TRUE,
    default_value VARCHAR(500),
    is_primary_key BOOLEAN DEFAULT FALSE,
    is_unique BOOLEAN DEFAULT FALSE,
    is_foreign_key BOOLEAN NOT NULL DEFAULT FALSE,
    ref_table_id BIGINT,
    ref_primary_key_fields JSONB,
    pk_generation_json JSONB,
    fk_display_mode VARCHAR(20) DEFAULT 'readonly',
    relation_cardinality VARCHAR(20),
    display_name TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_field_table FOREIGN KEY (table_id) REFERENCES dw_table_definitions(id) ON DELETE CASCADE,
    CONSTRAINT uk_field_name_table UNIQUE (table_id, field_name)
);

CREATE INDEX IF NOT EXISTS idx_dw_field_definitions_table ON dw_field_definitions(table_id);

-- =====================================================
-- 6. Foreign Keys Table (dw_foreign_keys)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_foreign_keys (
    id BIGSERIAL PRIMARY KEY,
    table_id BIGINT NOT NULL,
    field_id BIGINT NOT NULL,
    ref_table_id BIGINT NOT NULL,
    ref_field_id BIGINT NOT NULL,
    on_delete VARCHAR(20) DEFAULT 'NO ACTION',
    on_update VARCHAR(20) DEFAULT 'NO ACTION',
    CONSTRAINT fk_fk_table FOREIGN KEY (table_id) REFERENCES dw_table_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_fk_field FOREIGN KEY (field_id) REFERENCES dw_field_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_fk_ref_table FOREIGN KEY (ref_table_id) REFERENCES dw_table_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_fk_ref_field FOREIGN KEY (ref_field_id) REFERENCES dw_field_definitions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dw_foreign_keys_table ON dw_foreign_keys(table_id);

-- =====================================================
-- 7. Form Definitions Table (dw_form_definitions)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_form_definitions (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    form_name VARCHAR(100) NOT NULL,
    form_type VARCHAR(20) NOT NULL,
    config_json JSONB NOT NULL DEFAULT '{}',
    display_name TEXT,
    bound_table_id BIGINT,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_form_function_unit FOREIGN KEY (function_unit_id) REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT fk_form_bound_table FOREIGN KEY (bound_table_id) REFERENCES dw_table_definitions(id) ON DELETE SET NULL,
    CONSTRAINT uk_form_name_fu UNIQUE (function_unit_id, form_name),
    CONSTRAINT chk_form_type CHECK (form_type IN ('PROCESS', 'TASK', 'ACTION'))
);

CREATE INDEX IF NOT EXISTS idx_dw_form_definitions_fu ON dw_form_definitions(function_unit_id);

-- =====================================================
-- 8. Form Table Bindings Table (dw_form_table_bindings)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_form_table_bindings (
    id BIGSERIAL PRIMARY KEY,
    form_id BIGINT NOT NULL,
    table_id BIGINT,
    relation_table_id BIGINT,
    binding_type VARCHAR(20) NOT NULL,
    binding_mode VARCHAR(20) NOT NULL DEFAULT 'READONLY',
    foreign_key_field VARCHAR(100),
    binding_link_mode VARCHAR(32) NOT NULL DEFAULT 'structuralFk',
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT fk_binding_form FOREIGN KEY (form_id) REFERENCES dw_form_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_binding_table FOREIGN KEY (table_id) REFERENCES dw_table_definitions(id),
    CONSTRAINT chk_binding_type CHECK (binding_type IN ('PRIMARY', 'SUB', 'RELATED')),
    CONSTRAINT chk_binding_mode CHECK (binding_mode IN ('EDITABLE', 'READONLY'))
);

CREATE INDEX IF NOT EXISTS idx_dw_form_table_bindings_form ON dw_form_table_bindings(form_id);
CREATE INDEX IF NOT EXISTS idx_dw_form_table_bindings_table ON dw_form_table_bindings(table_id);

-- =====================================================
-- 8b. Main Table View Configs (dw_main_table_view_configs)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_main_table_view_configs (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL REFERENCES dw_function_units(id) ON DELETE CASCADE,
    main_table_id BIGINT NOT NULL REFERENCES dw_table_definitions(id) ON DELETE CASCADE,
    view_name VARCHAR(200) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    sort_config JSONB,
    filter_config JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_main_table_view_configs_fu ON dw_main_table_view_configs(function_unit_id);

-- =====================================================
-- 8c. Main Table View Fields (dw_main_table_view_fields)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_main_table_view_fields (
    id BIGSERIAL PRIMARY KEY,
    view_config_id BIGINT NOT NULL REFERENCES dw_main_table_view_configs(id) ON DELETE CASCADE,
    field_name VARCHAR(100) NOT NULL,
    display_label VARCHAR(200),
    column_width INTEGER,
    sort_order INTEGER NOT NULL DEFAULT 0,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    is_system_field BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_main_table_view_fields_config ON dw_main_table_view_fields(view_config_id, sort_order);

-- =====================================================
-- 9. Action Definitions Table (dw_action_definitions)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_action_definitions (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    action_name VARCHAR(100) NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    config_json JSONB NOT NULL DEFAULT '{}',
    icon VARCHAR(50),
    button_color VARCHAR(20),
    display_name TEXT,
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_action_function_unit FOREIGN KEY (function_unit_id) REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT uk_action_name_fu UNIQUE (function_unit_id, action_name)
);

CREATE INDEX IF NOT EXISTS idx_dw_action_definitions_fu ON dw_action_definitions(function_unit_id);

-- =====================================================
-- 10. Versions Table (dw_versions)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_versions (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    version_number VARCHAR(20) NOT NULL,
    change_log TEXT,
    snapshot_data BYTEA NOT NULL,
    published_by VARCHAR(50) NOT NULL,
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_version_function_unit FOREIGN KEY (function_unit_id) REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT uk_version_fu UNIQUE (function_unit_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_dw_versions_fu ON dw_versions(function_unit_id);

-- 11. Uploaded Files Table (dw_uploaded_files)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_uploaded_files (
    id BIGSERIAL PRIMARY KEY,
    stored_name VARCHAR(150) NOT NULL UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    file_size BIGINT NOT NULL,
    content BYTEA NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lock_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_dw_uploaded_files_created_at ON dw_uploaded_files(created_at DESC);

-- =====================================================
-- 12. Operation Logs Table (dw_operation_logs)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_operation_logs (
    id BIGSERIAL PRIMARY KEY,
    operator VARCHAR(50) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id BIGINT,
    description VARCHAR(500),
    details TEXT,
    ip_address VARCHAR(50),
    operation_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dw_operation_logs_operator ON dw_operation_logs(operator);
CREATE INDEX IF NOT EXISTS idx_dw_operation_logs_target ON dw_operation_logs(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_dw_operation_logs_time ON dw_operation_logs(operation_time);

-- =====================================================
-- Comments
-- =====================================================
COMMENT ON TABLE dw_icons IS 'Icon library for function units and actions';
COMMENT ON TABLE dw_function_units IS 'Function unit definitions (dw_* = developer workstation, uses BIGSERIAL IDs)';
COMMENT ON COLUMN dw_function_units.status IS 'Developer status: DRAFT / PUBLISHED / ARCHIVED. Note: sys_function_units uses a different lifecycle: DRAFT / VALIDATED / DEPLOYED / DEPRECATED';
COMMENT ON COLUMN dw_function_units.id IS 'BIGSERIAL auto-increment ID. Note: sys_function_units.id uses VARCHAR(64) UUID strings. ID type conversion is handled at the application layer during deployment.';
COMMENT ON TABLE dw_process_definitions IS 'BPMN process definitions';
COMMENT ON TABLE dw_table_definitions IS 'Data table definitions';
COMMENT ON TABLE dw_field_definitions IS 'Table field definitions';
COMMENT ON TABLE dw_foreign_keys IS 'Foreign key relationships between tables';
COMMENT ON TABLE dw_form_definitions IS 'Form definitions';
COMMENT ON TABLE dw_form_table_bindings IS 'Form-table binding relationships';
COMMENT ON TABLE dw_action_definitions IS 'Action/button definitions';
COMMENT ON COLUMN dw_action_definitions.action_type IS 'Valid types: PROCESS_SUBMIT, APPROVE, REJECT, N8N_ACTION, FORM_POPUP, API_CALL';
COMMENT ON TABLE dw_versions IS 'Function unit version history';
COMMENT ON TABLE dw_uploaded_files IS 'Database-backed uploaded files';
COMMENT ON COLUMN dw_uploaded_files.stored_name IS 'Opaque filename token exposed in /api/v1/upload/files/{storedName}';
COMMENT ON TABLE dw_operation_logs IS 'Operation audit logs';

-- =====================================================
-- 12. AI Sessions Table (dw_ai_sessions)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_ai_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE,
    function_unit_id BIGINT NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    current_phase VARCHAR(20) NOT NULL,
    mode VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_session_function_unit FOREIGN KEY (function_unit_id)
        REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_session_phase CHECK (current_phase IN ('REQUIREMENTS', 'DESIGN', 'GENERATION')),
    CONSTRAINT chk_ai_session_mode CHECK (mode IN ('NEW', 'MODIFY')),
    CONSTRAINT chk_ai_session_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_dw_ai_sessions_fu ON dw_ai_sessions(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_dw_ai_sessions_user ON dw_ai_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_dw_ai_sessions_status ON dw_ai_sessions(function_unit_id, user_id, status);

COMMENT ON TABLE dw_ai_sessions IS 'AI generation sessions for function units';

-- =====================================================
-- 13. AI Messages Table (dw_ai_messages)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_ai_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    phase VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_message_session FOREIGN KEY (session_id)
        REFERENCES dw_ai_sessions(session_id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_ai_message_phase CHECK (phase IN ('REQUIREMENTS', 'DESIGN', 'GENERATION'))
);

CREATE INDEX IF NOT EXISTS idx_dw_ai_messages_session ON dw_ai_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_dw_ai_messages_session_time ON dw_ai_messages(session_id, created_at);

COMMENT ON TABLE dw_ai_messages IS 'AI chat messages within sessions';

-- =====================================================
-- 14. AI Documents Table (dw_ai_documents)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_ai_documents (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    document_type VARCHAR(20) NOT NULL,
    version INTEGER NOT NULL,
    content TEXT NOT NULL,
    summary VARCHAR(500),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_document_function_unit FOREIGN KEY (function_unit_id)
        REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT uk_ai_document_version UNIQUE (function_unit_id, document_type, version),
    CONSTRAINT chk_ai_document_type CHECK (document_type IN ('REQUIREMENTS', 'DESIGN'))
);

CREATE INDEX IF NOT EXISTS idx_dw_ai_documents_fu ON dw_ai_documents(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_dw_ai_documents_fu_type ON dw_ai_documents(function_unit_id, document_type);

COMMENT ON TABLE dw_ai_documents IS 'AI generated requirement and design documents with versioning';

-- =====================================================
-- 15. Function Unit Access Table (dw_function_unit_access)
-- =====================================================
CREATE TABLE IF NOT EXISTS dw_function_unit_access (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    access_type VARCHAR(20) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_dw_fu_access_func_unit FOREIGN KEY (function_unit_id) REFERENCES dw_function_units(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dw_fu_access_func_unit ON dw_function_unit_access(function_unit_id);

COMMENT ON TABLE dw_function_unit_access IS 'Function unit access permissions for developer workstation';


-- =============================================================================
-- 05-admin-center-schema.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\05-admin-center-schema.sql
-- =============================================================================
-- =====================================================
-- Admin Center Schema - Admin Tables
-- Tables with admin_* prefix for admin-center specific features
-- Consolidated from backend/admin-center migration V200
-- Note: sys_* tables are managed by platform-security
-- =====================================================

-- =====================================================
-- 1. Password History (admin_password_history)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_password_history (
    id VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    password_hash VARCHAR(255) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    CONSTRAINT fk_password_history_user FOREIGN KEY (user_id) REFERENCES sys_users(id)
);

CREATE INDEX IF NOT EXISTS idx_password_history_user ON admin_password_history(user_id);
CREATE INDEX IF NOT EXISTS idx_password_history_created ON admin_password_history(created_at);

-- =====================================================
-- 2. Permission Delegations (admin_permission_delegations)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_permission_delegations (
    id VARCHAR(64) PRIMARY KEY,
    delegator_id VARCHAR(64) NOT NULL,
    delegatee_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    conditions JSONB,
    created_by VARCHAR(64),
    delegation_type VARCHAR(20) NOT NULL,
    revoke_reason TEXT,
    revoked_at TIMESTAMP(6) WITH TIME ZONE,
    revoked_by VARCHAR(64),
    valid_from TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    valid_to TIMESTAMP(6) WITH TIME ZONE,
    permission_id VARCHAR(64) NOT NULL,
    CONSTRAINT fk_deleg_delegator FOREIGN KEY (delegator_id) REFERENCES sys_users(id),
    CONSTRAINT fk_deleg_delegatee FOREIGN KEY (delegatee_id) REFERENCES sys_users(id),
    CONSTRAINT chk_delegation_type CHECK (delegation_type IN ('TEMPORARY', 'PROXY', 'TRANSFER'))
);

CREATE INDEX IF NOT EXISTS idx_delegation_delegator ON admin_permission_delegations(delegator_id);
CREATE INDEX IF NOT EXISTS idx_delegation_delegatee ON admin_permission_delegations(delegatee_id);
CREATE INDEX IF NOT EXISTS idx_delegation_status ON admin_permission_delegations(status);

-- =====================================================
-- 3. Permission Conflicts (admin_permission_conflicts)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_permission_conflicts (
    id VARCHAR(64) PRIMARY KEY,
    conflict_description TEXT,
    conflict_source1 VARCHAR(100) NOT NULL,
    conflict_source2 VARCHAR(100) NOT NULL,
    detected_at TIMESTAMP(6) WITH TIME ZONE,
    resolution_result TEXT,
    resolution_strategy VARCHAR(30),
    resolved_at TIMESTAMP(6) WITH TIME ZONE,
    resolved_by VARCHAR(64),
    status VARCHAR(20) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    permission_id VARCHAR(64) NOT NULL,
    CONSTRAINT chk_resolution_strategy CHECK (resolution_strategy IN ('DENY', 'ALLOW', 'HIGHEST_PRIVILEGE', 'LOWEST_PRIVILEGE', 'LATEST', 'MANUAL'))
);

CREATE INDEX IF NOT EXISTS idx_conflict_user ON admin_permission_conflicts(user_id);
CREATE INDEX IF NOT EXISTS idx_conflict_status ON admin_permission_conflicts(status);

-- =====================================================
-- 4. Permission Change History (admin_permission_change_history)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_permission_change_history (
    id VARCHAR(36) PRIMARY KEY,
    change_type VARCHAR(50) NOT NULL,
    target_user_id VARCHAR(36),
    target_role_id VARCHAR(36),
    target_permission_id VARCHAR(36),
    old_value VARCHAR(500),
    new_value VARCHAR(500),
    reason VARCHAR(500),
    changed_by VARCHAR(36) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(50),
    user_agent VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_pch_target_user_id ON admin_permission_change_history(target_user_id);
CREATE INDEX IF NOT EXISTS idx_pch_change_type ON admin_permission_change_history(change_type);
CREATE INDEX IF NOT EXISTS idx_pch_changed_at ON admin_permission_change_history(changed_at);

-- =====================================================
-- 5. Alert Rules (admin_alert_rules)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_alert_rules (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    metric_name VARCHAR(50),
    operator VARCHAR(20),
    threshold DOUBLE PRECISION,
    duration INTEGER,
    severity VARCHAR(20),
    notify_channels VARCHAR(500),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_alert_rules_name ON admin_alert_rules(name);
CREATE INDEX IF NOT EXISTS idx_alert_rules_enabled ON admin_alert_rules(enabled);

-- =====================================================
-- 6. Alerts (admin_alerts)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_alerts (
    id VARCHAR(36) PRIMARY KEY,
    rule_id VARCHAR(36),
    title VARCHAR(200) NOT NULL,
    message TEXT,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    metric_value DOUBLE PRECISION,
    acknowledged_by VARCHAR(36),
    acknowledged_at TIMESTAMP,
    resolved_by VARCHAR(36),
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_alert_rule FOREIGN KEY (rule_id) REFERENCES admin_alert_rules(id)
);

CREATE INDEX IF NOT EXISTS idx_alert_status ON admin_alerts(status);
CREATE INDEX IF NOT EXISTS idx_alert_severity ON admin_alerts(severity);

-- =====================================================
-- 7. System Configs (admin_system_configs)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_system_configs (
    id VARCHAR(36) PRIMARY KEY,
    category VARCHAR(50) NOT NULL,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_name VARCHAR(100) NOT NULL,
    config_value TEXT,
    default_value VARCHAR(500),
    value_type VARCHAR(20),
    description VARCHAR(500),
    encrypted BOOLEAN DEFAULT FALSE,
    editable BOOLEAN DEFAULT TRUE,
    version INTEGER,
    environment VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_config_category ON admin_system_configs(category);
CREATE INDEX IF NOT EXISTS idx_config_key ON admin_system_configs(config_key);

-- =====================================================
-- 8. System Logs (admin_system_logs)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_system_logs (
    id VARCHAR(36) PRIMARY KEY,
    log_type VARCHAR(50) NOT NULL,
    log_level VARCHAR(20) NOT NULL,
    module VARCHAR(100),
    action VARCHAR(100),
    message TEXT,
    stack_trace TEXT,
    user_id VARCHAR(64),
    user_name VARCHAR(100),
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    request_url VARCHAR(500),
    request_method VARCHAR(10),
    response_time BIGINT,
    response_status INTEGER,
    request_body TEXT,
    response_body TEXT,
    extra_data TEXT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_log_type ON admin_system_logs(log_type);
CREATE INDEX IF NOT EXISTS idx_log_level ON admin_system_logs(log_level);
CREATE INDEX IF NOT EXISTS idx_log_timestamp ON admin_system_logs(timestamp);
CREATE INDEX IF NOT EXISTS idx_log_user ON admin_system_logs(user_id);

-- =====================================================
-- 9. Security Policies (admin_security_policies)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_security_policies (
    id VARCHAR(36) PRIMARY KEY,
    policy_type VARCHAR(50) NOT NULL UNIQUE,
    policy_name VARCHAR(100) NOT NULL,
    policy_config TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64)
);

-- =====================================================
-- 10. Data Permission Rules (admin_data_permission_rules)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_data_permission_rules (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    permission_type VARCHAR(20) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    data_scope VARCHAR(30) NOT NULL,
    custom_filter TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    priority INTEGER DEFAULT 100,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dp_rule_type ON admin_data_permission_rules(permission_type);
CREATE INDEX IF NOT EXISTS idx_dp_rule_target ON admin_data_permission_rules(target_type, target_id);

-- =====================================================
-- 11. Column Permissions (admin_column_permissions)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_column_permissions (
    id VARCHAR(36) PRIMARY KEY,
    rule_id VARCHAR(36) NOT NULL,
    column_name VARCHAR(100) NOT NULL,
    visible BOOLEAN DEFAULT TRUE,
    masked BOOLEAN DEFAULT FALSE,
    mask_type VARCHAR(50),
    mask_expression VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_col_perm_rule FOREIGN KEY (rule_id) REFERENCES admin_data_permission_rules(id)
);

CREATE INDEX IF NOT EXISTS idx_col_perm_rule ON admin_column_permissions(rule_id);

-- =====================================================
-- 12. Audit Logs (admin_audit_logs)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id VARCHAR(36) PRIMARY KEY,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(64),
    resource_name VARCHAR(200),
    user_id VARCHAR(64) NOT NULL,
    user_name VARCHAR(100),
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    old_value TEXT,
    new_value TEXT,
    change_details TEXT,
    success BOOLEAN,
    failure_reason VARCHAR(500),
    duration_ms INTEGER,
    request_method VARCHAR(10),
    request_path VARCHAR(500),
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_action ON admin_audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_user ON admin_audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON admin_audit_logs(timestamp);

-- =====================================================
-- 13. Config History (admin_config_history)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_config_history (
    id VARCHAR(36) PRIMARY KEY,
    config_id VARCHAR(36) NOT NULL,
    config_key VARCHAR(100) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    old_version INTEGER,
    new_version INTEGER,
    change_reason VARCHAR(500),
    changed_by VARCHAR(64),
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_config_history_key ON admin_config_history(config_key);
CREATE INDEX IF NOT EXISTS idx_config_history_time ON admin_config_history(changed_at);

-- =====================================================
-- 14. Log Retention Policies (admin_log_retention_policies)
-- =====================================================
CREATE TABLE IF NOT EXISTS admin_log_retention_policies (
    id VARCHAR(36) PRIMARY KEY,
    log_type VARCHAR(50) NOT NULL UNIQUE,
    retention_days INTEGER NOT NULL,
    archive_after_days INTEGER,
    archive_location VARCHAR(500),
    compression_enabled BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(64)
);

-- =====================================================
-- LDAP 同步审计表 (ac_ldap_sync_audit)
-- 用途：①运维可视化 LDAP 用户/AD 组同步历史与失败原因；
--      ②增量同步以最近一次 SUCCESS 记录的 snapshot_at 作为 AD whenChanged 水位起点。
-- 对应 Flyway admin-center V212（建表） + V213（扩展 Hermes AD Group 同步审计字段）
-- =====================================================
CREATE TABLE IF NOT EXISTS ac_ldap_sync_audit (
    id            VARCHAR(64)   NOT NULL,
    sync_type     VARCHAR(20)   NOT NULL,           -- FULL / INCREMENTAL / HERMES_AD_GROUP / HERMES_AD_INCR
    status        VARCHAR(20)   NOT NULL,           -- RUNNING / SUCCESS / FAILED
    total_fetched INTEGER,
    upserted      INTEGER,
    failed        INTEGER,
    message       VARCHAR(1000),                    -- 失败原因/摘要（脱敏，不含密码/DN 明文）
    snapshot_at   TIMESTAMP,                        -- 本次同步开始时刻，作为下次增量水位
    started_at    TIMESTAMP     NOT NULL,
    finished_at   TIMESTAMP,
    -- V213 Hermes AD Group 同步扩展列
    high_water_mark   VARCHAR(1000),                -- 增量同步水位标记
    groups            VARCHAR(2000),                -- 本次同步涉及的 AD 组清单（逗号分隔）
    success_count     INTEGER,                      -- 成功 upsert 数量
    skipped_missing_key INTEGER,                    -- 因缺少 employeeID 而跳过的条数
    insert_count      INTEGER,                      -- 新用户插入数
    update_count      INTEGER,                      -- 已存在用户更新数
    duration_ms       BIGINT,                       -- 同步耗时（毫秒）
    CONSTRAINT pk_ac_ldap_sync_audit PRIMARY KEY (id)
);

-- 按 (sync_type, status, started_at) 查询「最近一次成功的增量/全量」时使用
CREATE INDEX IF NOT EXISTS idx_ac_ldap_sync_audit_type_status_started
    ON ac_ldap_sync_audit (sync_type, status, started_at DESC);

-- 列表分页（started_at 倒序）
CREATE INDEX IF NOT EXISTS idx_ac_ldap_sync_audit_started
    ON ac_ldap_sync_audit (started_at DESC);

-- =====================================================
-- Comments
-- =====================================================
COMMENT ON TABLE admin_password_history IS 'Password history for password policy enforcement';
COMMENT ON TABLE admin_permission_delegations IS 'Permission delegation records';
COMMENT ON TABLE admin_permission_conflicts IS 'Permission conflict tracking';
COMMENT ON TABLE admin_alert_rules IS 'Alert rules configuration';
COMMENT ON TABLE admin_system_configs IS 'System configuration';
COMMENT ON TABLE admin_audit_logs IS 'Audit trail';
COMMENT ON TABLE ac_ldap_sync_audit IS 'LDAP 用户/AD 组同步审计日志（record of full/incremental/user/adgroup sync operations）';


-- =============================================================================
-- 06-add-deployment-rollback-columns.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\06-add-deployment-rollback-columns.sql
-- =============================================================================
-- =====================================================
-- Add missing rollback columns to sys_function_unit_deployments
-- Date: 2026-02-04
-- =====================================================

-- Add rollback tracking columns
ALTER TABLE sys_function_unit_deployments 
ADD COLUMN IF NOT EXISTS started_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS rollback_reason TEXT,
ADD COLUMN IF NOT EXISTS rollback_by VARCHAR(64),
ADD COLUMN IF NOT EXISTS rollback_at TIMESTAMP;

-- Add comment for documentation
COMMENT ON COLUMN sys_function_unit_deployments.started_at IS 'Deployment start timestamp';
COMMENT ON COLUMN sys_function_unit_deployments.rollback_reason IS 'Reason for rollback';
COMMENT ON COLUMN sys_function_unit_deployments.rollback_by IS 'User ID who performed the rollback';
COMMENT ON COLUMN sys_function_unit_deployments.rollback_at IS 'Rollback timestamp';


-- =============================================================================
-- 07-add-action-definitions-table.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\07-add-action-definitions-table.sql
-- =============================================================================
-- =====================================================
-- Platform Security Schema - Action Definitions Table
-- Table for storing action definitions in all environments
-- This table is separate from dw_action_definitions which only exists in dev
-- =====================================================

-- =====================================================
-- Action Definitions (sys_action_definitions)
-- Stores action definitions that are deployed with function units
-- =====================================================
CREATE TABLE IF NOT EXISTS sys_action_definitions (
    id VARCHAR(64) PRIMARY KEY,
    function_unit_id VARCHAR(64) NOT NULL,
    action_name VARCHAR(100) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    display_name TEXT,
    config_json JSONB DEFAULT '{}'::jsonb,
    icon VARCHAR(50),
    button_color VARCHAR(20),
    is_default BOOLEAN DEFAULT false,
    created_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    CONSTRAINT fk_action_function_unit FOREIGN KEY (function_unit_id) REFERENCES sys_function_units(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sys_action_function_unit ON sys_action_definitions(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_sys_action_name ON sys_action_definitions(action_name);
CREATE INDEX IF NOT EXISTS idx_sys_action_type ON sys_action_definitions(action_type);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_action_name_fu ON sys_action_definitions(function_unit_id, action_name);

COMMENT ON TABLE sys_action_definitions IS 'Action definitions deployed with function units (all environments)';
COMMENT ON COLUMN sys_action_definitions.function_unit_id IS 'Reference to sys_function_units';
COMMENT ON COLUMN sys_action_definitions.action_type IS 'Action type: APPROVE, REJECT, FORM_POPUP, API_CALL, etc.';
COMMENT ON COLUMN sys_action_definitions.config_json IS 'Additional configuration in JSON format (formId, apiEndpoint, etc.)';


-- =============================================================================
-- 08-add-function-unit-versioning.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\08-add-function-unit-versioning.sql
-- =============================================================================
-- =====================================================
-- Function Unit Versioning Migration
-- Adds version tracking columns to function unit tables
-- Requirements: 8.1, 8.2, 8.3, 8.4, 8.7
-- =====================================================

-- =====================================================
-- 1. Add versioning columns to dw_function_units
-- =====================================================

-- Add version column (semantic version format: MAJOR.MINOR.PATCH)
ALTER TABLE dw_function_units 
ADD COLUMN IF NOT EXISTS version VARCHAR(20) NOT NULL DEFAULT '1.0.0';

-- Add is_active flag (only one version per function unit should be active)
ALTER TABLE dw_function_units 
ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Add deployed_at timestamp (when this version was deployed)
ALTER TABLE dw_function_units 
ADD COLUMN IF NOT EXISTS deployed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Add previous_version_id (links to the previous version for version history)
ALTER TABLE dw_function_units 
ADD COLUMN IF NOT EXISTS previous_version_id BIGINT NULL;

-- Add foreign key constraint for previous_version_id
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_dw_function_unit_previous_version') THEN
        ALTER TABLE dw_function_units ADD CONSTRAINT fk_dw_function_unit_previous_version
        FOREIGN KEY (previous_version_id) REFERENCES dw_function_units(id) ON DELETE SET NULL;
    END IF;
END $$;

-- Create index for version queries (function unit name + version)
CREATE INDEX IF NOT EXISTS idx_dw_function_unit_version 
ON dw_function_units(name, version);

-- Create index for active version queries (function unit name + is_active)
CREATE INDEX IF NOT EXISTS idx_dw_function_unit_active 
ON dw_function_units(name, is_active);

-- Create index for deployed_at for sorting by deployment time
CREATE INDEX IF NOT EXISTS idx_dw_function_unit_deployed_at 
ON dw_function_units(deployed_at);

-- Add comments
COMMENT ON COLUMN dw_function_units.version IS 'Semantic version number (MAJOR.MINOR.PATCH)';
COMMENT ON COLUMN dw_function_units.is_active IS 'Whether this version is currently active (only one version per function unit should be active)';
COMMENT ON COLUMN dw_function_units.deployed_at IS 'Timestamp when this version was deployed';
COMMENT ON COLUMN dw_function_units.previous_version_id IS 'Reference to the previous version of this function unit';

-- =====================================================
-- 2. Add versioning columns to sys_function_units
-- =====================================================

-- Note: sys_function_units already has a 'version' column, but we need to ensure it follows semantic versioning
-- We'll add the other versioning columns

-- Add is_active flag
ALTER TABLE sys_function_units 
ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Add deployed_at timestamp (use imported_at as default if not set)
ALTER TABLE sys_function_units 
ADD COLUMN IF NOT EXISTS deployed_at TIMESTAMP;

-- Set deployed_at to imported_at for existing records
UPDATE sys_function_units 
SET deployed_at = COALESCE(imported_at, created_at, CURRENT_TIMESTAMP)
WHERE deployed_at IS NULL;

-- Make deployed_at NOT NULL after setting values
ALTER TABLE sys_function_units 
ALTER COLUMN deployed_at SET NOT NULL;

-- Add previous_version_id (links to the previous version)
-- Note: sys_function_units uses VARCHAR(64) for id, not BIGINT
ALTER TABLE sys_function_units 
ADD COLUMN IF NOT EXISTS previous_version_id VARCHAR(64) NULL;

-- Add foreign key constraint for previous_version_id
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_sys_function_unit_previous_version') THEN
        ALTER TABLE sys_function_units ADD CONSTRAINT fk_sys_function_unit_previous_version
        FOREIGN KEY (previous_version_id) REFERENCES sys_function_units(id) ON DELETE SET NULL;
    END IF;
END $$;

-- Create index for version queries (function unit name + version)
CREATE INDEX IF NOT EXISTS idx_sys_function_unit_version 
ON sys_function_units(name, version);

-- Create index for active version queries (function unit name + is_active)
CREATE INDEX IF NOT EXISTS idx_sys_function_unit_active 
ON sys_function_units(name, is_active);

-- Create index for deployed_at for sorting by deployment time
CREATE INDEX IF NOT EXISTS idx_sys_function_unit_deployed_at 
ON sys_function_units(deployed_at);

-- Add comments
COMMENT ON COLUMN sys_function_units.is_active IS 'Whether this version is currently active (only one version per function unit should be active)';
COMMENT ON COLUMN sys_function_units.deployed_at IS 'Timestamp when this version was deployed';
COMMENT ON COLUMN sys_function_units.previous_version_id IS 'Reference to the previous version of this function unit';

-- =====================================================
-- 3. Add function_unit_version_id to dw_process_definitions
-- =====================================================

-- Add function_unit_version_id column (links to specific version of function unit)
ALTER TABLE dw_process_definitions 
ADD COLUMN IF NOT EXISTS function_unit_version_id BIGINT;

-- Set function_unit_version_id to current function_unit_id for existing records
-- This assumes existing records should link to the active version
UPDATE dw_process_definitions pd
SET function_unit_version_id = pd.function_unit_id
WHERE function_unit_version_id IS NULL;

-- Make function_unit_version_id NOT NULL after setting values
ALTER TABLE dw_process_definitions 
ALTER COLUMN function_unit_version_id SET NOT NULL;

-- Add foreign key constraint
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_dw_process_def_function_unit_version') THEN
        ALTER TABLE dw_process_definitions ADD CONSTRAINT fk_dw_process_def_function_unit_version
        FOREIGN KEY (function_unit_version_id) REFERENCES dw_function_units(id) ON DELETE CASCADE;
    END IF;
END $$;

-- Create index for version queries
CREATE INDEX IF NOT EXISTS idx_dw_process_def_version 
ON dw_process_definitions(function_unit_version_id);

-- Add comment
COMMENT ON COLUMN dw_process_definitions.function_unit_version_id IS 'Reference to the specific version of the function unit this process definition belongs to';

-- =====================================================
-- 4. Add function_unit_version_id to up_process_instance
-- =====================================================

-- Add function_unit_version_id column (links to specific version of function unit)
-- Note: up_process_instance uses VARCHAR(64) for id
ALTER TABLE up_process_instance 
ADD COLUMN IF NOT EXISTS function_unit_version_id BIGINT;

-- Note: We cannot automatically populate this for existing records without knowing
-- which function unit they belong to. This will need to be handled by a separate
-- data migration script after the schema is in place.

-- Add comment
COMMENT ON COLUMN up_process_instance.function_unit_version_id IS 'Reference to the specific version of the function unit this process instance is bound to';

-- Create index for version queries
CREATE INDEX IF NOT EXISTS idx_up_process_instance_version 
ON up_process_instance(function_unit_version_id);

-- =====================================================
-- Migration Complete
-- =====================================================

-- Print success message
DO $$
BEGIN
    RAISE NOTICE 'Function unit versioning migration completed successfully';
    RAISE NOTICE 'Added version tracking columns to dw_function_units and sys_function_units';
    RAISE NOTICE 'Added function_unit_version_id to dw_process_definitions and up_process_instance';
    RAISE NOTICE 'Created indexes for performance optimization';
END $$;


-- =============================================================================
-- 10-add-approval-order-column.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\10-add-approval-order-column.sql
-- =============================================================================
-- Add approval_order column to sys_function_unit_approvals table
-- Supports approval sequence in multi-level approval workflows

-- Add column (if not exists)
ALTER TABLE sys_function_unit_approvals 
ADD COLUMN IF NOT EXISTS approval_order INTEGER DEFAULT 1;

-- Make approver_id nullable (may not be assigned when approval is created)
ALTER TABLE sys_function_unit_approvals 
ALTER COLUMN approver_id DROP NOT NULL;

-- Add comments
COMMENT ON COLUMN sys_function_unit_approvals.approval_order IS 'Approval sequence order for multi-level approval workflows';
COMMENT ON COLUMN sys_function_unit_approvals.approver_id IS 'Approver ID, nullable at creation, required at approval time';

-- Set default value for existing records
UPDATE sys_function_unit_approvals 
SET approval_order = 1 
WHERE approval_order IS NULL;


-- =============================================================================
-- 11-add-unique-enabled-constraint.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\11-add-unique-enabled-constraint.sql
-- =============================================================================
-- Add unique constraint: ensure only one enabled version per function unit code
-- Date: 2026-02-06
-- Purpose: Prevent multiple versions of the same function unit from being enabled simultaneously

-- Create unique partial index
-- Unique constraint only applies when enabled = true
CREATE UNIQUE INDEX IF NOT EXISTS idx_function_unit_code_enabled 
ON sys_function_units (code) 
WHERE enabled = true;

-- Verify constraint
-- Query enabled version count per code, should all be 0 or 1
SELECT code, COUNT(*) as enabled_count
FROM sys_function_units
WHERE enabled = true
GROUP BY code
HAVING COUNT(*) > 1;

-- If the above query returns any results, there is data violating the constraint
-- Fix the data first, then create the constraint

COMMENT ON INDEX idx_function_unit_code_enabled IS 'Ensure only one enabled version per function unit code';


-- =============================================================================
-- 12-add-enabled-field-to-dw-function-units.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\12-add-enabled-field-to-dw-function-units.sql
-- =============================================================================
-- Add enabled field to dw_function_units table
-- Date: 2026-02-07
-- Purpose: Implement version management, allow enabling/disabling specific versions

-- Add enabled field, default true
ALTER TABLE dw_function_units 
ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT true;

-- Create index for query performance
CREATE INDEX IF NOT EXISTS idx_dw_function_units_enabled 
ON dw_function_units(enabled);

-- Create unique partial index: ensure only one enabled version per function unit code
CREATE UNIQUE INDEX IF NOT EXISTS idx_dw_function_unit_code_enabled 
ON dw_function_units (code) 
WHERE enabled = true;

-- Verify constraint
-- Query enabled version count per code, should all be 0 or 1
SELECT code, COUNT(*) as enabled_count
FROM dw_function_units
WHERE enabled = true
GROUP BY code
HAVING COUNT(*) > 1;

-- Add comments
COMMENT ON COLUMN dw_function_units.enabled IS 'Whether this version is enabled (only enabled versions are visible to users)';
COMMENT ON INDEX idx_dw_function_unit_code_enabled IS 'Ensure only one enabled version per function unit code';


-- =============================================================================
-- 13-add-notification-table.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\13-add-notification-table.sql
-- =============================================================================
-- =====================================================
-- Notification Table (up_notification)
-- 站内通知表
-- =====================================================
CREATE TABLE IF NOT EXISTS up_notification (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    type VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    link VARCHAR(500),
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_up_notification_user_id ON up_notification(user_id);
CREATE INDEX IF NOT EXISTS idx_up_notification_created_at ON up_notification(created_at);
CREATE INDEX IF NOT EXISTS idx_up_notification_user_created ON up_notification(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_up_notification_user_read ON up_notification(user_id, is_read);

COMMENT ON TABLE up_notification IS '站内通知';


-- =============================================================================
-- 15-bi-management-schema.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\15-bi-management-schema.sql
-- =============================================================================
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


-- =============================================================================
-- 16-add-decision-and-relations-tables.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\16-add-decision-and-relations-tables.sql
-- =============================================================================
-- =====================================================
-- Additional schema for decision definitions, table relations,
-- form type rename, task form fields, and form stage bindings.
-- Corresponds to Flyway V301, V305, V306, V307, V308.
-- =====================================================

-- Decision Definitions (V301)
CREATE TABLE IF NOT EXISTS dw_decision_definitions (
    id               BIGSERIAL       PRIMARY KEY,
    function_unit_id BIGINT          NOT NULL,
    decision_key     VARCHAR(100)    NOT NULL,
    decision_name    VARCHAR(200),
    dmn_xml          TEXT,
    hit_policy       VARCHAR(20),
    description      TEXT,
    created_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_decision_function_unit
        FOREIGN KEY (function_unit_id) REFERENCES dw_function_units(id) ON DELETE CASCADE,
    CONSTRAINT uk_decision_fu_key
        UNIQUE (function_unit_id, decision_key)
);
CREATE INDEX IF NOT EXISTS idx_decision_function_unit_id ON dw_decision_definitions(function_unit_id);

-- Table Relations (V305)
CREATE TABLE IF NOT EXISTS dw_table_relations (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL REFERENCES dw_function_units(id) ON DELETE CASCADE,
    source_table_id BIGINT NOT NULL REFERENCES dw_table_definitions(id) ON DELETE CASCADE,
    source_field_name VARCHAR(100) NOT NULL,
    relation_type VARCHAR(20) NOT NULL CHECK (relation_type IN ('ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_MANY')),
    target_table_id BIGINT NOT NULL REFERENCES dw_table_definitions(id) ON DELETE CASCADE,
    target_field_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_table_relations_fu_id ON dw_table_relations(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_table_relations_source ON dw_table_relations(source_table_id);
CREATE INDEX IF NOT EXISTS idx_table_relations_target ON dw_table_relations(target_table_id);

-- Form type rename (V306)
-- Rename MAIN→PROCESS, SUB→TASK, remove POPUP; align with Java FormType enum
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_form_type'
          AND conrelid = 'dw_form_definitions'::regclass
    ) THEN
        ALTER TABLE dw_form_definitions DROP CONSTRAINT chk_form_type;
    END IF;

    UPDATE dw_form_definitions SET form_type = 'PROCESS' WHERE form_type = 'MAIN';
    UPDATE dw_form_definitions SET form_type = 'TASK'    WHERE form_type = 'SUB';
    UPDATE dw_form_definitions SET form_type = 'ACTION'  WHERE form_type = 'POPUP';

    ALTER TABLE dw_form_definitions
        ADD CONSTRAINT chk_form_type CHECK (form_type IN ('PROCESS', 'TASK', 'ACTION'));
END $$;

-- Task form fields (V307)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='dw_form_definitions' AND column_name='field_permissions') THEN
        ALTER TABLE dw_form_definitions ADD COLUMN field_permissions JSONB DEFAULT '{}';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='dw_form_definitions' AND column_name='show_live_values') THEN
        ALTER TABLE dw_form_definitions ADD COLUMN show_live_values BOOLEAN NOT NULL DEFAULT TRUE;
    END IF;
END $$;

-- Form Stage Bindings (V308)
CREATE TABLE IF NOT EXISTS dw_form_stage_bindings (
    id            BIGSERIAL PRIMARY KEY,
    form_id       BIGINT NOT NULL REFERENCES dw_form_definitions(id) ON DELETE CASCADE,
    stage_id      VARCHAR(255) NOT NULL,
    stage_name    VARCHAR(255),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(form_id, stage_id)
);
CREATE INDEX IF NOT EXISTS idx_form_stage_bindings_stage_id ON dw_form_stage_bindings(stage_id);

-- =============================================================================
-- 18-add-read-only-to-form-stage-bindings.sql (sync with deploy/init-scripts/00-schema/)
-- =============================================================================
ALTER TABLE dw_form_stage_bindings
ADD COLUMN IF NOT EXISTS read_only BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN dw_form_stage_bindings.read_only IS 'Whether the form bound to this stage is read-only';


-- =============================================================================
-- 17-add-lock-version-to-user-portal-tables.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\17-add-lock-version-to-user-portal-tables.sql
-- =============================================================================
-- =====================================================
-- 17. Add lock_version column to user-portal tables
-- =====================================================
-- JPA entities ProcessInstance, DelegationRule, ProcessDraft
-- use @Version with lock_version column for optimistic locking,
-- but the database tables were missing this column.

ALTER TABLE up_process_instance
ADD COLUMN IF NOT EXISTS lock_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE up_delegation_rule
ADD COLUMN IF NOT EXISTS lock_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE up_process_draft
ADD COLUMN IF NOT EXISTS lock_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN up_process_instance.lock_version IS 'Optimistic locking version';
COMMENT ON COLUMN up_delegation_rule.lock_version IS 'Optimistic locking version';
COMMENT ON COLUMN up_process_draft.lock_version IS 'Optimistic locking version';

-- sys_users also uses @Version with lock_version for optimistic locking
ALTER TABLE sys_users
ADD COLUMN IF NOT EXISTS lock_version BIGINT DEFAULT 0;

COMMENT ON COLUMN sys_users.lock_version IS 'Optimistic locking version';


-- =============================================================================
-- 18-add-lock-version-to-form-definitions.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\18-add-lock-version-to-form-definitions.sql
-- =============================================================================
-- =====================================================
-- 18. Add lock_version column to dw_form_definitions
-- =====================================================
-- FormDefinition entity uses @Version with lock_version
-- for optimistic locking.

ALTER TABLE dw_form_definitions
ADD COLUMN IF NOT EXISTS lock_version BIGINT DEFAULT 0;

COMMENT ON COLUMN dw_form_definitions.lock_version IS 'Optimistic locking version';


-- =============================================================================
-- 19-add-up-change-history.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\19-add-up-change-history.sql
-- =============================================================================
-- =====================================================
-- User Portal: change history (up_change_history)
-- Aligns with com.portal.entity.ChangeHistory and
-- backend/user-portal/.../V402__create_change_history.sql
-- =====================================================

CREATE TABLE IF NOT EXISTS up_change_history (
    id                  BIGSERIAL PRIMARY KEY,
    process_instance_id VARCHAR(64) NOT NULL,
    task_instance_id    VARCHAR(64),
    stage_id            VARCHAR(255),
    user_id             VARCHAR(64) NOT NULL,
    timestamp           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    field_name          VARCHAR(255) NOT NULL,
    old_value           TEXT,
    new_value           TEXT,
    change_type         VARCHAR(30) NOT NULL DEFAULT 'FIELD_UPDATE',
    sub_table_name      VARCHAR(255),
    row_identifier      VARCHAR(255),
    is_concurrent       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_change_history_process ON up_change_history(process_instance_id);
CREATE INDEX IF NOT EXISTS idx_change_history_task ON up_change_history(task_instance_id);
CREATE INDEX IF NOT EXISTS idx_change_history_timestamp ON up_change_history(process_instance_id, timestamp);

COMMENT ON TABLE up_change_history IS 'Per-field change history for portal process/task forms';


-- =============================================================================
-- 20-add-members-table.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\20-add-members-table.sql
-- =============================================================================
-- =====================================================
-- Developer Workstation: members
-- Aligns with com.developer.entity.Member (@Table members)
-- =====================================================

CREATE TABLE IF NOT EXISTS members (
    id                   BIGSERIAL PRIMARY KEY,
    username             VARCHAR(50)  NOT NULL UNIQUE,
    full_name            VARCHAR(100) NOT NULL,
    email                VARCHAR(255) NOT NULL,
    employee_id          VARCHAR(20),
    business_unit_id     VARCHAR(50),
    business_unit_name   VARCHAR(100),
    role                 VARCHAR(50),
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(50),
    updated_by           VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_members_email ON members(email);
CREATE INDEX IF NOT EXISTS idx_members_active ON members(active);

COMMENT ON TABLE members IS 'Developer workstation member directory (separate from sys_users)';


-- =============================================================================
-- 21-add-rt-relation-tables.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\21-add-rt-relation-tables.sql
-- =============================================================================
-- =====================================================
-- Relation Table: rt_* definitions, access, audit, view, lookup
-- Consolidates admin-center V202 and developer-workstation V1
-- Aligns with admin-center RelationTable* and developer Relation* entities
-- =====================================================

-- 1. Table Definitions (rt_table_definitions)
CREATE TABLE IF NOT EXISTS rt_table_definitions (
    id                  BIGSERIAL       PRIMARY KEY,
    table_name          VARCHAR(100)    NOT NULL UNIQUE,
    display_name        VARCHAR(200),
    deployed_display_name VARCHAR(200),
    description         TEXT,
    status              VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    portal_visible      BOOLEAN         NOT NULL DEFAULT FALSE,
    current_version     INTEGER         DEFAULT 0,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(64),
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(64)
);

COMMENT ON TABLE rt_table_definitions IS 'Relation Table definitions with metadata and versioning';
COMMENT ON COLUMN rt_table_definitions.status IS 'DRAFT / DEPLOYED / ROLLBACK';
COMMENT ON COLUMN rt_table_definitions.portal_visible IS 'Controls visibility in User Portal';
COMMENT ON COLUMN rt_table_definitions.current_version IS 'Current deployed version number';
COMMENT ON COLUMN rt_table_definitions.deployed_display_name IS 'Display name captured on last deploy; Table Data uses this while status is UPDATED/ROLLBACK';

-- 2. Field Definitions (rt_field_definitions)
CREATE TABLE IF NOT EXISTS rt_field_definitions (
    id                  BIGSERIAL       PRIMARY KEY,
    table_id            BIGINT          NOT NULL REFERENCES rt_table_definitions(id),
    field_name          VARCHAR(100)    NOT NULL,
    data_type           VARCHAR(50)     NOT NULL,
    length              INTEGER,
    precision_value     INTEGER,
    scale               INTEGER,
    nullable            BOOLEAN         DEFAULT TRUE,
    is_primary_key      BOOLEAN         DEFAULT FALSE,
    default_value       VARCHAR(500),
    display_name        TEXT,
    sort_order          INTEGER         NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rt_field_table_id ON rt_field_definitions(table_id);

COMMENT ON TABLE rt_field_definitions IS 'Field definitions for Relation Tables';

-- 3. Table Versions (rt_table_versions)
CREATE TABLE IF NOT EXISTS rt_table_versions (
    id                  BIGSERIAL       PRIMARY KEY,
    table_id            BIGINT          NOT NULL REFERENCES rt_table_definitions(id),
    version_number      INTEGER         NOT NULL,
    snapshot_data       TEXT            NOT NULL,
    deployed_by         VARCHAR(64)     NOT NULL,
    deployed_at         TIMESTAMP       NOT NULL,
    change_log          TEXT
);

CREATE INDEX IF NOT EXISTS idx_rt_version_table_id ON rt_table_versions(table_id);

COMMENT ON TABLE rt_table_versions IS 'Version snapshots created on each deployment';

-- 4. Table Access (rt_table_access)
CREATE TABLE IF NOT EXISTS rt_table_access (
    id                  VARCHAR(64)     PRIMARY KEY,
    table_id            BIGINT          NOT NULL REFERENCES rt_table_definitions(id),
    target_type         VARCHAR(20)     NOT NULL,
    target_id           VARCHAR(64)     NOT NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_rt_access_table_id ON rt_table_access(table_id);
CREATE INDEX IF NOT EXISTS idx_rt_access_target ON rt_table_access(target_type, target_id);

COMMENT ON TABLE rt_table_access IS 'Business Role access configuration for Relation Tables';
COMMENT ON COLUMN rt_table_access.target_type IS 'ROLE';

-- 5. Audit Logs (rt_audit_logs)
CREATE TABLE IF NOT EXISTS rt_audit_logs (
    id                  VARCHAR(64)     PRIMARY KEY,
    table_id            BIGINT          NOT NULL,
    table_name          VARCHAR(100)    NOT NULL,
    row_id              VARCHAR(100),
    action              VARCHAR(20)     NOT NULL,
    old_value           TEXT,
    new_value           TEXT,
    operator_id         VARCHAR(64)     NOT NULL,
    operator_name       VARCHAR(100),
    operated_at         TIMESTAMP       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rt_audit_table ON rt_audit_logs(table_id);
CREATE INDEX IF NOT EXISTS idx_rt_audit_action ON rt_audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_rt_audit_operator ON rt_audit_logs(operator_id);
CREATE INDEX IF NOT EXISTS idx_rt_audit_time ON rt_audit_logs(operated_at);

COMMENT ON TABLE rt_audit_logs IS 'Audit log for Relation Table data changes';
COMMENT ON COLUMN rt_audit_logs.action IS 'ADD / UPDATE / DELETE / STATUS_CHANGE';

-- 5b. Row Data (rt_table_data_rows) — JSON storage, no per-table physical tables
CREATE TABLE IF NOT EXISTS rt_table_data_rows (
    id          BIGSERIAL       PRIMARY KEY,
    table_id    BIGINT          NOT NULL REFERENCES rt_table_definitions(id) ON DELETE CASCADE,
    row_id      VARCHAR(100)    NOT NULL,
    data        JSONB           NOT NULL DEFAULT '{}',
    status      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(64),
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64),
    CONSTRAINT uk_rt_data_rows_table_row UNIQUE (table_id, row_id)
);

CREATE INDEX IF NOT EXISTS idx_rt_data_rows_table_id ON rt_table_data_rows(table_id);
CREATE INDEX IF NOT EXISTS idx_rt_data_rows_table_status ON rt_table_data_rows(table_id, status);

COMMENT ON TABLE rt_table_data_rows IS 'Relation Table row data stored as JSON';

-- 6. View Configs (rt_view_configs)
CREATE TABLE IF NOT EXISTS rt_view_configs (
    id                  BIGSERIAL       PRIMARY KEY,
    binding_id          BIGINT          NOT NULL,
    table_id            BIGINT          NOT NULL,
    field_config        TEXT,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rt_view_config_binding ON rt_view_configs(binding_id);
CREATE INDEX IF NOT EXISTS idx_rt_view_config_table ON rt_view_configs(table_id);

COMMENT ON TABLE rt_view_configs IS 'View configuration for bound Relation Tables';
COMMENT ON COLUMN rt_view_configs.binding_id IS 'FK to dw_form_table_bindings.id';
COMMENT ON COLUMN rt_view_configs.field_config IS 'JSON: selected fields list and order';

-- 7. View Fields (rt_view_fields)
CREATE TABLE IF NOT EXISTS rt_view_fields (
    id                  BIGSERIAL       PRIMARY KEY,
    view_config_id      BIGINT          NOT NULL REFERENCES rt_view_configs(id) ON DELETE CASCADE,
    field_name          VARCHAR(100)    NOT NULL,
    display_label       VARCHAR(200),
    column_width        INTEGER,
    sort_order          INTEGER         NOT NULL,
    visible             BOOLEAN         NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_rt_view_field_config ON rt_view_fields(view_config_id);

COMMENT ON TABLE rt_view_fields IS 'Field-level configuration within a View';

-- 8. Lookup Configs (rt_lookup_configs)
CREATE TABLE IF NOT EXISTS rt_lookup_configs (
    id                  BIGSERIAL       PRIMARY KEY,
    form_id             BIGINT          NOT NULL,
    component_id        VARCHAR(100)    NOT NULL,
    view_config_id      BIGINT          REFERENCES rt_view_configs(id),
    table_id            BIGINT          NOT NULL,
    search_fields       TEXT,
    display_field       VARCHAR(100),
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rt_lookup_form ON rt_lookup_configs(form_id);
CREATE INDEX IF NOT EXISTS idx_rt_lookup_component ON rt_lookup_configs(form_id, component_id);

COMMENT ON TABLE rt_lookup_configs IS 'Lookup component configuration for form-create';
COMMENT ON COLUMN rt_lookup_configs.search_fields IS 'JSON array: fields used for search in User Portal';

-- Parity with Flyway V2__add_relation_table_id_to_bindings (older DBs may lack this column)
ALTER TABLE dw_form_table_bindings ALTER COLUMN table_id DROP NOT NULL;
ALTER TABLE dw_form_table_bindings ADD COLUMN IF NOT EXISTS relation_table_id BIGINT;

-- Partial index for RELATED bindings
CREATE INDEX IF NOT EXISTS idx_binding_relation_table ON dw_form_table_bindings(relation_table_id)
    WHERE relation_table_id IS NOT NULL;


-- =============================================================================
-- 22-add-lock-version-to-sys-roles.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\22-add-lock-version-to-sys-roles.sql
-- =============================================================================
-- =====================================================
-- sys_roles: optimistic locking (lock_version)
-- Aligns with com.platform.security.entity.Role @Version
-- and platform-security Flyway V210__add_lock_version_to_users_and_roles.sql
-- (sys_users.lock_version is already in 01-platform-security-schema.sql)
-- =====================================================

ALTER TABLE sys_roles
    ADD COLUMN IF NOT EXISTS lock_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sys_roles.lock_version IS 'Optimistic locking version';


-- =============================================================================
-- 23-widen-up-process-instance-business-key.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\23-widen-up-process-instance-business-key.sql
-- =============================================================================
-- =====================================================
-- up_process_instance.business_key: align with JPA
-- user-portal & developer-workstation ProcessInstance
-- use @Column(length = 255); base 03 script used VARCHAR(100).
-- =====================================================

ALTER TABLE up_process_instance
    ALTER COLUMN business_key TYPE VARCHAR(255);

COMMENT ON COLUMN up_process_instance.business_key IS 'Business key (aligned with entity length 255)';


-- =============================================================================
-- 24-add-multi-instance-execution-table.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\24-add-multi-instance-execution-table.sql
-- =============================================================================
-- =====================================================
-- Multi-Instance Execution Table
-- Tracks multi-instance sub-process execution metadata
-- for dynamic task dispatch based on sub-table data
-- =====================================================

-- =====================================================
-- wf_multi_instance_execution
-- Records multi-instance sub-process execution state
-- =====================================================
CREATE TABLE IF NOT EXISTS wf_multi_instance_execution (
    id BIGSERIAL PRIMARY KEY,
    process_instance_id VARCHAR(64) NOT NULL,
    activity_id VARCHAR(255) NOT NULL,
    activity_name VARCHAR(255),
    sub_table_name VARCHAR(100) NOT NULL,
    sub_table_id VARCHAR(64) NOT NULL,
    collection_variable_name VARCHAR(255) NOT NULL,
    execution_mode VARCHAR(20) NOT NULL DEFAULT 'PARALLEL',
    total_instances INTEGER NOT NULL,
    completed_instances INTEGER NOT NULL DEFAULT 0,
    active_instances INTEGER NOT NULL DEFAULT 0,
    cancelled_instances INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    started_time TIMESTAMP NOT NULL,
    completed_time TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_mi_exec_mode CHECK (execution_mode IN ('PARALLEL', 'SEQUENTIAL')),
    CONSTRAINT chk_mi_exec_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED'))
);

-- Indexes for wf_multi_instance_execution
CREATE INDEX IF NOT EXISTS idx_mi_exec_process_instance ON wf_multi_instance_execution(process_instance_id);
CREATE INDEX IF NOT EXISTS idx_mi_exec_status ON wf_multi_instance_execution(status);
CREATE INDEX IF NOT EXISTS idx_mi_exec_activity_id ON wf_multi_instance_execution(activity_id);
CREATE INDEX IF NOT EXISTS idx_mi_exec_started_time ON wf_multi_instance_execution(started_time);

-- Comments
COMMENT ON TABLE wf_multi_instance_execution IS '多实例子流程执行记录表，用于跟踪基于子表数据的动态任务分发';
COMMENT ON COLUMN wf_multi_instance_execution.process_instance_id IS '流程实例ID';
COMMENT ON COLUMN wf_multi_instance_execution.activity_id IS 'BPMN活动节点ID';
COMMENT ON COLUMN wf_multi_instance_execution.activity_name IS 'BPMN活动节点名称';
COMMENT ON COLUMN wf_multi_instance_execution.sub_table_name IS '关联的子表物理表名';
COMMENT ON COLUMN wf_multi_instance_execution.sub_table_id IS '关联的子表ID（来自TableDefinition）';
COMMENT ON COLUMN wf_multi_instance_execution.collection_variable_name IS 'Flowable集合变量名称';
COMMENT ON COLUMN wf_multi_instance_execution.execution_mode IS '执行模式: PARALLEL（并行）, SEQUENTIAL（顺序）';
COMMENT ON COLUMN wf_multi_instance_execution.total_instances IS '总实例数（等于子表数据行数）';
COMMENT ON COLUMN wf_multi_instance_execution.completed_instances IS '已完成实例数';
COMMENT ON COLUMN wf_multi_instance_execution.active_instances IS '活跃实例数';
COMMENT ON COLUMN wf_multi_instance_execution.cancelled_instances IS '已取消实例数';
COMMENT ON COLUMN wf_multi_instance_execution.status IS '执行状态: ACTIVE（执行中）, COMPLETED（已完成）, CANCELLED（已取消）';
COMMENT ON COLUMN wf_multi_instance_execution.started_time IS '开始执行时间';
COMMENT ON COLUMN wf_multi_instance_execution.completed_time IS '完成时间';


-- =============================================================================
-- 25-add-row-version-to-sub-tables.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\25-add-row-version-to-sub-tables.sql
-- =============================================================================
-- =====================================================
-- Add row_version Column to Existing Sub-Tables
-- Migration script for multi-instance task dispatch feature
-- Adds optimistic locking support to all SUB type tables
-- =====================================================

-- =====================================================
-- 说明：
-- 本脚本为已存在的子表（table_type = 'SUB'）添加 row_version 列
-- row_version 用于实现乐观锁，防止多实例子任务并发编辑冲突
-- 
-- 执行逻辑：
-- 1. 查询 dw_table_definitions 表，找出所有 table_type = 'SUB' 的表
-- 2. 对每个子表执行 ALTER TABLE ADD COLUMN IF NOT EXISTS
-- 3. 使用 IF NOT EXISTS 确保脚本可重复执行
-- =====================================================

DO $$
DECLARE
    sub_table_record RECORD;
    alter_sql TEXT;
BEGIN
    -- 遍历所有 SUB 类型的表定义
    FOR sub_table_record IN 
        SELECT table_name 
        FROM dw_table_definitions 
        WHERE table_type = 'SUB'
    LOOP
        -- 构建 ALTER TABLE 语句
        alter_sql := format(
            'ALTER TABLE %I ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 1',
            sub_table_record.table_name
        );
        
        -- 执行 ALTER TABLE
        BEGIN
            EXECUTE alter_sql;
            RAISE NOTICE 'Added row_version column to table: %', sub_table_record.table_name;
        EXCEPTION
            WHEN OTHERS THEN
                RAISE WARNING 'Failed to add row_version to table %: %', 
                    sub_table_record.table_name, SQLERRM;
        END;
    END LOOP;
    
    RAISE NOTICE 'Migration completed: row_version column added to all SUB tables';
END $$;

-- =====================================================
-- 验证脚本（可选）
-- 查询所有子表的 row_version 列是否存在
-- =====================================================
-- SELECT 
--     td.table_name,
--     td.table_type,
--     CASE 
--         WHEN EXISTS (
--             SELECT 1 
--             FROM information_schema.columns 
--             WHERE table_name = td.table_name 
--             AND column_name = 'row_version'
--         ) THEN 'EXISTS'
--         ELSE 'MISSING'
--     END AS row_version_status
-- FROM dw_table_definitions td
-- WHERE td.table_type = 'SUB'
-- ORDER BY td.table_name;


-- =============================================================================
-- 26-add-dw-deployment-jobs.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\26-add-dw-deployment-jobs.sql
-- =============================================================================
-- dw_deployment_jobs: developer-workstation 部署到 admin-center 的异步任务持久化（与 Flyway V309 对齐）
CREATE TABLE IF NOT EXISTS dw_deployment_jobs (
    id VARCHAR(36) PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    target_admin_url VARCHAR(1024),
    status VARCHAR(32) NOT NULL,
    progress INTEGER,
    message TEXT,
    version_number VARCHAR(64),
    change_log TEXT,
    steps_json TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dw_deployment_job_function_unit FOREIGN KEY (function_unit_id) REFERENCES dw_function_units(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dw_deployment_jobs_function_unit_started ON dw_deployment_jobs(function_unit_id, started_at DESC);


-- =============================================================================
-- 27-add-up-process-instance-catalog-pin.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\27-add-up-process-instance-catalog-pin.sql
-- =============================================================================
-- 与 user-portal Flyway V404 对齐：发起流程时钉死功能单元目录版本
ALTER TABLE up_process_instance ADD COLUMN IF NOT EXISTS function_unit_catalog_id VARCHAR(64);
ALTER TABLE up_process_instance ADD COLUMN IF NOT EXISTS function_unit_code VARCHAR(50);
ALTER TABLE up_process_instance ADD COLUMN IF NOT EXISTS function_unit_version_label VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_up_pi_fu_catalog ON up_process_instance(function_unit_catalog_id);
CREATE INDEX IF NOT EXISTS idx_up_pi_fu_code_ver ON up_process_instance(function_unit_code, function_unit_version_label);


-- =============================================================================
-- 28-dw-function-unit-dev-groups.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\28-dw-function-unit-dev-groups.sql
-- =============================================================================
-- 与 developer-workstation Flyway V310 一致
CREATE TABLE IF NOT EXISTS dw_function_unit_dev_groups (
    id              BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL REFERENCES dw_function_units(id) ON DELETE CASCADE,
    virtual_group_id VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(64),
    CONSTRAINT uq_dw_fu_dev_group UNIQUE (function_unit_id, virtual_group_id)
);

CREATE INDEX IF NOT EXISTS idx_dw_fu_dev_group_fu ON dw_function_unit_dev_groups(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_dw_fu_dev_group_vg ON dw_function_unit_dev_groups(virtual_group_id);


-- =============================================================================
-- 29-up-permission-request-submitted-by.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\29-up-permission-request-submitted-by.sql
-- =============================================================================
-- 与 backend/user-portal Flyway V405 对齐：历史库可能缺少 submitted_by_user_id，导致 GET /permissions/requests JDBC 查询 500
ALTER TABLE up_permission_request
    ADD COLUMN IF NOT EXISTS submitted_by_user_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_up_permission_request_submitted_by
    ON up_permission_request (submitted_by_user_id);

COMMENT ON COLUMN up_permission_request.submitted_by_user_id IS '登录提交人 userId；为空表示历史数据（视同本人提交）';


-- =============================================================================
-- 30-widen-flowable-identitylink-columns.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\30-widen-flowable-identitylink-columns.sql
-- =============================================================================
-- =============================================================================
-- Widen Flowable ACT_*_IDENTITYLINK varchar(255) columns
-- =============================================================================
-- Symptom (workflow-engine): Task completion fails with
--   PSQLException: ERROR: value too long for type character varying(255)
--   insert into ACT_HI_IDENTITYLINK (...)
-- Cause: Flowable default DDL uses VARCHAR(255) for GROUP_ID_, SCOPE_* , etc.
--        Long virtual group ids, scope ids, or definition ids exceed 255.
-- Fix: Widen to VARCHAR(4000) on runtime + historic identity link tables.
--
-- Apply (dev example):
--   docker exec -i platform-postgres-dev psql -U platform_dev -d workflow_platform_dev -v ON_ERROR_STOP=1 -f -
-- =============================================================================

ALTER TABLE IF EXISTS act_ru_identitylink
    ALTER COLUMN group_id_ TYPE VARCHAR(4000),
    ALTER COLUMN type_ TYPE VARCHAR(4000),
    ALTER COLUMN user_id_ TYPE VARCHAR(4000),
    ALTER COLUMN scope_id_ TYPE VARCHAR(4000),
    ALTER COLUMN sub_scope_id_ TYPE VARCHAR(4000),
    ALTER COLUMN scope_type_ TYPE VARCHAR(4000),
    ALTER COLUMN scope_definition_id_ TYPE VARCHAR(4000);

ALTER TABLE IF EXISTS act_hi_identitylink
    ALTER COLUMN group_id_ TYPE VARCHAR(4000),
    ALTER COLUMN type_ TYPE VARCHAR(4000),
    ALTER COLUMN user_id_ TYPE VARCHAR(4000),
    ALTER COLUMN scope_id_ TYPE VARCHAR(4000),
    ALTER COLUMN sub_scope_id_ TYPE VARCHAR(4000),
    ALTER COLUMN scope_type_ TYPE VARCHAR(4000),
    ALTER COLUMN scope_definition_id_ TYPE VARCHAR(4000);


-- =============================================================================
-- 31-widen-flowable-act-hi-comment-columns.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\31-widen-flowable-act-hi-comment-columns.sql
-- =============================================================================
-- =============================================================================
-- Widen Flowable ACT_HI_COMMENT columns (PostgreSQL)
-- =============================================================================
-- Symptom: Task completion fails with
--   PSQLException: value too long for type character varying(255)
--   insert into ACT_HI_COMMENT (..., USER_ID_, ..., ACTION_, MESSAGE_, FULL_MSG_) ...
-- Cause: Legacy DDL may keep MESSAGE_/ACTION_/TYPE_/USER_ID_ at 255, or FULL_MSG_ as
--        varchar instead of bytea. Any of these can trigger the error depending on payload.
-- Fix: Widen text columns; convert FULL_MSG_ to bytea when it is still a character type.
--
-- Re-run anytime (idempotent). If act_hi_comment does not exist yet, skip via IF EXISTS.
-- Note: Flowable usually creates act_* tables after this init step; workflow-engine also applies
--       the same widen on PostgreSQL startup (see FlowableActHiCommentSchemaRepair).
-- =============================================================================

-- String columns (split so one failure does not block the rest)
ALTER TABLE IF EXISTS act_hi_comment ALTER COLUMN message_ TYPE TEXT;
ALTER TABLE IF EXISTS act_hi_comment ALTER COLUMN action_ TYPE VARCHAR(4000);
ALTER TABLE IF EXISTS act_hi_comment ALTER COLUMN type_ TYPE VARCHAR(4000);
ALTER TABLE IF EXISTS act_hi_comment ALTER COLUMN user_id_ TYPE VARCHAR(4000);

-- FULL_MSG_ must be bytea in Flowable 7; old installs sometimes used varchar(255).
DO $widen_full_msg$
DECLARE
    dt text;
BEGIN
    SELECT c.data_type INTO dt
    FROM information_schema.columns c
    WHERE c.table_schema = 'public'
      AND c.table_name = 'act_hi_comment'
      AND c.column_name = 'full_msg_';
    IF dt IS NULL THEN
        RETURN;
    END IF;
    IF dt = 'bytea' THEN
        RETURN;
    END IF;
    EXECUTE $sql$
        ALTER TABLE act_hi_comment
        ALTER COLUMN full_msg_ TYPE bytea
        USING CASE
            WHEN full_msg_ IS NULL THEN NULL::bytea
            ELSE convert_to(full_msg_::text, 'UTF8')
        END
    $sql$;
END
$widen_full_msg$;


-- =============================================================================
-- 32-add-dw-form-table-binding-subview-columns.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\32-add-dw-form-table-binding-subview-columns.sql
-- =============================================================================
-- =====================================================
-- Developer Workstation: Form table binding extra columns
-- =====================================================
-- Fixes runtime 500 when developer-workstation queries dw_form_table_bindings
-- with columns that are present in JPA entity but missing in init schema:
--   - sub_list_view_id
--   - sub_mode
--
-- Keep idempotent via IF NOT EXISTS.
-- =====================================================

ALTER TABLE dw_form_table_bindings
  ADD COLUMN IF NOT EXISTS sub_list_view_id BIGINT;

ALTER TABLE dw_form_table_bindings
  ADD COLUMN IF NOT EXISTS sub_mode VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_dw_form_table_bindings_sub_list_view
  ON dw_form_table_bindings(sub_list_view_id);


-- =============================================================================
-- 33-dw-sub-table-view-tables.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\33-dw-sub-table-view-tables.sql
-- =============================================================================
-- =====================================================
-- Developer Workstation: sub-table list view storage
-- =====================================================
-- JPA entities: SubTableViewConfig / SubTableViewField
--
-- Mirrors Flyway V311 (backend/.../V311__create_sub_table_view_tables.sql).
-- Docker Compose dev disables Flyway by default — these tables MUST exist in
-- init scripts or INSERT/SELECT on create SUB binding + FULL mode will fail with:
--   ERROR: relation "dw_sub_table_view_configs" does not exist
-- and poison the enclosing transaction ("current transaction is aborted").
-- =====================================================

CREATE TABLE IF NOT EXISTS dw_sub_table_view_configs (
    id BIGSERIAL PRIMARY KEY,
    binding_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_sub_table_view_configs_binding_id
    ON dw_sub_table_view_configs(binding_id);

CREATE TABLE IF NOT EXISTS dw_sub_table_view_fields (
    id BIGSERIAL PRIMARY KEY,
    view_config_id BIGINT NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    display_label VARCHAR(200),
    column_width INTEGER,
    sort_order INTEGER NOT NULL DEFAULT 0,
    visible BOOLEAN NOT NULL DEFAULT TRUE
);

-- FK with ON DELETE CASCADE: without it, deleting a form-table binding (e.g. on re-import or FU delete)
-- orphans the config row, and the orphan's unique binding_id later collides with a new binding's id.
-- Clean any pre-existing orphans first so the constraint can be added on legacy/seeded DBs.
DELETE FROM dw_sub_table_view_fields f
 WHERE NOT EXISTS (SELECT 1 FROM dw_sub_table_view_configs c WHERE c.id = f.view_config_id)
    OR f.view_config_id IN (
       SELECT c.id FROM dw_sub_table_view_configs c
        WHERE NOT EXISTS (SELECT 1 FROM dw_form_table_bindings b WHERE b.id = c.binding_id));
DELETE FROM dw_sub_table_view_configs c
 WHERE NOT EXISTS (SELECT 1 FROM dw_form_table_bindings b WHERE b.id = c.binding_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_sub_view_config_binding') THEN
        ALTER TABLE dw_sub_table_view_configs
            ADD CONSTRAINT fk_sub_view_config_binding
            FOREIGN KEY (binding_id) REFERENCES dw_form_table_bindings(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_sub_view_field_config') THEN
        ALTER TABLE dw_sub_table_view_fields
            ADD CONSTRAINT fk_sub_view_field_config
            FOREIGN KEY (view_config_id) REFERENCES dw_sub_table_view_configs(id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dw_sub_table_view_fields_config_id
    ON dw_sub_table_view_fields(view_config_id);

CREATE INDEX IF NOT EXISTS idx_dw_sub_table_view_fields_sort_order
    ON dw_sub_table_view_fields(view_config_id, sort_order);


-- =============================================================================
-- 34-dw-link-form-components.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\34-dw-link-form-components.sql
-- =============================================================================
-- =====================================================
-- Developer Workstation: Link Form Components
-- =====================================================
-- Mirrors Flyway migration
--   backend/developer-workstation/.../V312__add_sub_mode_and_link_components.sql
-- so the schema is present in dev where Flyway is disabled
-- (see docker-compose.dev.yml -> SPRING_FLYWAY_ENABLED=false; init-scripts are the
-- source of truth in dev per docs/schema-and-migration.md).
--
-- Fixes runtime 500 from
--   GET /api/v1/function-units/{id}/link-form-components
-- when the developer-workstation backend tries to query
-- dw_link_form_components but the table is absent.
--
-- The `sub_mode` column added by V312 is already provided by
-- 32-add-dw-form-table-binding-subview-columns.sql, so this script
-- focuses on the two link-form tables only.
--
-- All operations are idempotent.
-- =====================================================

-- Curated Link Form component definitions (functionUnit-scoped).
CREATE TABLE IF NOT EXISTS dw_link_form_components (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL,
    component_name VARCHAR(200) NOT NULL,
    linked_form_id BIGINT NOT NULL,
    display_field VARCHAR(100),
    link_text VARCHAR(200) DEFAULT '详情',
    column_label VARCHAR(200),
    sort_order INTEGER NOT NULL DEFAULT 0,
    config_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_link_form_components_function_unit
    ON dw_link_form_components(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_link_form_components_sort_order
    ON dw_link_form_components(function_unit_id, sort_order);

-- Per-row data captured by Link Form widgets (one row per sub-table row).
CREATE TABLE IF NOT EXISTS dw_link_form_data (
    id BIGSERIAL PRIMARY KEY,
    component_id BIGINT NOT NULL,
    sub_table_row_id BIGINT NOT NULL,
    form_data JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_link_form_data_component_row
    ON dw_link_form_data(component_id, sub_table_row_id);
CREATE INDEX IF NOT EXISTS idx_link_form_data_row
    ON dw_link_form_data(sub_table_row_id);

COMMENT ON TABLE dw_link_form_components IS
    'Designer-curated Link Form widget definitions (function-unit scoped). '
    'Negative componentIds on list-view columns are GENERIC, not from this table; '
    'positive ids reference rows here.';
COMMENT ON TABLE dw_link_form_data IS
    'Per-row Link Form data captured at runtime, keyed by component_id + sub_table_row_id.';


-- =============================================================================
-- 34-extend-function-unit-status-check.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\34-extend-function-unit-status-check.sql
-- =============================================================================
-- Extend sys_function_units.status CHECK to support ARCHIVED (soft delete)
-- Note: INIT was removed in 35-drop-init-function-unit-status.sql; use DRAFT for import/restore.
ALTER TABLE sys_function_units DROP CONSTRAINT IF EXISTS chk_func_unit_status;
ALTER TABLE sys_function_units ADD CONSTRAINT chk_func_unit_status
    CHECK (status IN ('DRAFT', 'VALIDATED', 'DEPLOYED', 'DEPRECATED', 'ARCHIVED'));

COMMENT ON COLUMN sys_function_units.status IS 'Lifecycle: DRAFT (imported/restored), VALIDATED, DEPLOYED, DEPRECATED, ARCHIVED (removed from portal)';


-- =============================================================================
-- 35-drop-init-function-unit-status.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\35-drop-init-function-unit-status.sql
-- =============================================================================
-- Consolidate INIT into DRAFT for function unit lifecycle
UPDATE sys_function_units SET status = 'DRAFT' WHERE status = 'INIT';

ALTER TABLE sys_function_units DROP CONSTRAINT IF EXISTS chk_func_unit_status;
ALTER TABLE sys_function_units ADD CONSTRAINT chk_func_unit_status
    CHECK (status IN ('DRAFT', 'VALIDATED', 'DEPLOYED', 'DEPRECATED', 'ARCHIVED'));

COMMENT ON COLUMN sys_function_units.status IS 'Lifecycle: DRAFT (imported/restored), VALIDATED, DEPLOYED, DEPRECATED, ARCHIVED (removed from portal)';


-- =============================================================================
-- 36-sys-function-units-description.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\36-sys-function-units-description.sql
-- =============================================================================
-- Rename sys_function_units.display_name → description (align with admin-center FunctionUnit entity)

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'sys_function_units'
          AND column_name = 'display_name'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'sys_function_units'
          AND column_name = 'description'
    ) THEN
        ALTER TABLE sys_function_units RENAME COLUMN display_name TO description;
    END IF;
END
$$;

COMMENT ON COLUMN sys_function_units.description IS 'Function unit description imported from Developer Workstation manifest';


-- =============================================================================
-- 37-sys-action-definitions-description.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\37-sys-action-definitions-description.sql
-- =============================================================================
-- Rename sys_action_definitions.display_name → description (align with admin-center ActionDefinition entity)

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'sys_action_definitions'
          AND column_name = 'display_name'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'sys_action_definitions'
          AND column_name = 'description'
    ) THEN
        ALTER TABLE sys_action_definitions RENAME COLUMN display_name TO description;
    END IF;
END
$$;

COMMENT ON COLUMN sys_action_definitions.description IS 'Action description imported from Developer Workstation';


-- =============================================================================
-- 38-dw-main-table-view-tables.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\38-dw-main-table-view-tables.sql
-- =============================================================================
-- Main Table View configs (one default view per table: MAIN + SUB)
CREATE TABLE IF NOT EXISTS dw_main_table_view_configs (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL REFERENCES dw_function_units(id) ON DELETE CASCADE,
    main_table_id BIGINT NOT NULL REFERENCES dw_table_definitions(id) ON DELETE CASCADE,
    view_name VARCHAR(200) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    sort_config JSONB,
    filter_config JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_main_table_view_configs_fu ON dw_main_table_view_configs(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_main_table_view_configs_table ON dw_main_table_view_configs(main_table_id);
-- Default view is now per-table (MAIN + SUB), not per-function-unit.
-- Drop the legacy per-FU unique index explicitly so already-running databases are converted
-- (snapshot-style CREATE IF NOT EXISTS does not replace an existing index).
DROP INDEX IF EXISTS idx_main_table_view_configs_fu_default;
CREATE UNIQUE INDEX IF NOT EXISTS idx_mtv_configs_table_default
    ON dw_main_table_view_configs(main_table_id) WHERE is_default = TRUE;

CREATE TABLE IF NOT EXISTS dw_main_table_view_fields (
    id BIGSERIAL PRIMARY KEY,
    view_config_id BIGINT NOT NULL REFERENCES dw_main_table_view_configs(id) ON DELETE CASCADE,
    field_name VARCHAR(100) NOT NULL,
    display_label VARCHAR(200),
    column_width INTEGER,
    sort_order INTEGER NOT NULL DEFAULT 0,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    is_system_field BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_main_table_view_fields_config ON dw_main_table_view_fields(view_config_id, sort_order);


-- =============================================================================
-- 39-add-request-id-config-to-table-definitions.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\39-add-request-id-config-to-table-definitions.sql
-- =============================================================================
-- Migration: Add request_id_config column to dw_table_definitions
-- JPA entity TableDefinition.requestIdConfig was added but existing DBs lack the column.

ALTER TABLE dw_table_definitions
    ADD COLUMN IF NOT EXISTS request_id_config JSONB;

COMMENT ON COLUMN dw_table_definitions.request_id_config IS 'Request ID generation configuration for auto-generated IDs';


-- =============================================================================
-- 40-superset-schema.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\40-superset-schema.sql
-- =============================================================================
-- =====================================================
-- Superset BI — schema only (idempotent)
-- Tables/roles/data are managed by Superset's own CLI:
--   superset db upgrade   (incremental, never drops data)
--   superset init          (idempotent, skips existing)
-- =====================================================
CREATE SCHEMA IF NOT EXISTS superset;


-- =============================================================================
-- 41-dw-function-unit-tags.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\41-dw-function-unit-tags.sql
-- =============================================================================
-- Align with Flyway V322: custom tags on function units (no seed data — tags are user-defined only)
ALTER TABLE dw_function_units
    ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN dw_function_units.tags IS 'User-defined tags for filtering (JSON string array)';

-- PostgreSQL helper: check if a JSONB array contains ALL elements from a text array.
-- Used by server-side tag filter (AND semantics).
CREATE OR REPLACE FUNCTION jsonb_exists_all(data jsonb, keys text[])
RETURNS boolean
LANGUAGE sql
IMMUTABLE
RETURNS NULL ON NULL INPUT
AS 'SELECT data ?& keys';


-- =============================================================================
-- 44-ac-ldap-sync-audit.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\44-ac-ldap-sync-audit.sql
-- =============================================================================
-- =====================================================
-- Admin Center: LDAP 同步审计表 ac_ldap_sync_audit
-- =====================================================
-- 对应 Flyway admin-center V212（建表）+ V213（扩展 Hermes AD Group 同步审计字段）
-- 幂等：IF NOT EXISTS，可重复执行。
-- =====================================================

CREATE TABLE IF NOT EXISTS ac_ldap_sync_audit (
    id            VARCHAR(64)   NOT NULL,
    sync_type     VARCHAR(20)   NOT NULL,           -- FULL / INCREMENTAL / HERMES_AD_GROUP / HERMES_AD_INCR
    status        VARCHAR(20)   NOT NULL,           -- RUNNING / SUCCESS / FAILED
    total_fetched INTEGER,
    upserted      INTEGER,
    failed        INTEGER,
    message       VARCHAR(1000),                    -- 失败原因/摘要（脱敏）
    snapshot_at   TIMESTAMP,                        -- 本次同步开始时刻，作为下次增量水位
    started_at    TIMESTAMP     NOT NULL,
    finished_at   TIMESTAMP,
    -- V213 Hermes AD Group 同步扩展列（幂等：手动 DO 块检查）
    high_water_mark   VARCHAR(1000),
    groups            VARCHAR(2000),
    success_count     INTEGER,
    skipped_missing_key INTEGER,
    insert_count      INTEGER,
    update_count      INTEGER,
    duration_ms       BIGINT,
    CONSTRAINT pk_ac_ldap_sync_audit PRIMARY KEY (id)
);

-- 按 (sync_type, status, started_at) 查询最近一次成功的增量/全量
CREATE INDEX IF NOT EXISTS idx_ac_ldap_sync_audit_type_status_started
    ON ac_ldap_sync_audit (sync_type, status, started_at DESC);

-- 列表分页
CREATE INDEX IF NOT EXISTS idx_ac_ldap_sync_audit_started
    ON ac_ldap_sync_audit (started_at DESC);


-- =============================================================================
-- 45-dw-email-connections.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\45-dw-email-connections.sql
-- =============================================================================
-- Align with Flyway V5 (developer-workstation): SMTP connections per function unit
CREATE TABLE IF NOT EXISTS dw_email_connections (
    id BIGSERIAL PRIMARY KEY,
    connection_uid VARCHAR(64) NOT NULL UNIQUE,
    function_unit_id BIGINT NOT NULL REFERENCES dw_function_units(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    connection_type VARCHAR(30) NOT NULL DEFAULT 'SMTP',
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL DEFAULT 587,
    username VARCHAR(255),
    credential_encrypted TEXT,
    from_email VARCHAR(255) NOT NULL,
    from_name VARCHAR(100),
    use_tls BOOLEAN DEFAULT TRUE,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(function_unit_id, name)
);

-- Inbound IMAP endpoint (nullable; used when direction is INBOUND/BOTH). Align with Flyway V8.
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS imap_host VARCHAR(255);
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS imap_port INTEGER;
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS imap_use_ssl BOOLEAN DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_dw_email_conn_fu ON dw_email_connections(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_dw_email_conn_uid ON dw_email_connections(connection_uid);

COMMENT ON TABLE dw_email_connections IS 'Per–Function Unit SMTP/IMAP connections (design-time source for email Send Tasks and inbound monitor)';


-- =============================================================================
-- 46-sys-email-connections.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\46-sys-email-connections.sql
-- =============================================================================
-- Align with Flyway V2 (admin-center): synced email connections for runtime credential lookup
CREATE TABLE IF NOT EXISTS sys_email_connections (
    id VARCHAR(64) PRIMARY KEY,
    function_unit_id VARCHAR(64) NOT NULL REFERENCES sys_function_units(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    connection_type VARCHAR(30) NOT NULL DEFAULT 'SMTP',
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL DEFAULT 587,
    username VARCHAR(255),
    credential_encrypted TEXT,
    from_email VARCHAR(255) NOT NULL,
    from_name VARCHAR(100),
    use_tls BOOLEAN DEFAULT TRUE,
    enabled BOOLEAN DEFAULT TRUE,
    synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(function_unit_id, name)
);

-- Inbound IMAP endpoint (nullable; synced from developer-workstation). Align with admin Flyway V215.
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS imap_host VARCHAR(255);
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS imap_port INTEGER;
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS imap_use_ssl BOOLEAN DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_sys_email_conn_fu ON sys_email_connections(function_unit_id);

COMMENT ON TABLE sys_email_connections IS 'SMTP/IMAP connections synced from developer workstation on Function Unit import/deploy';


-- =============================================================================
-- 47-dw-email-templates.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\47-dw-email-templates.sql
-- =============================================================================
-- Align with Flyway V6 (developer-workstation): email templates per function unit
CREATE TABLE IF NOT EXISTS dw_email_templates (
    id BIGSERIAL PRIMARY KEY,
    function_unit_id BIGINT NOT NULL REFERENCES dw_function_units(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    subject VARCHAR(500),
    body_html TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(function_unit_id, name)
);

CREATE INDEX IF NOT EXISTS idx_dw_email_tpl_fu ON dw_email_templates(function_unit_id);

COMMENT ON TABLE dw_email_templates IS 'Per–Function Unit email templates (HTML body + variable placeholders for Send Tasks)';


-- =============================================================================
-- 48-dw-email-monitor-rules.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\48-dw-email-monitor-rules.sql
-- =============================================================================
-- Align with Flyway V7 (developer-workstation): inbound email monitoring
-- (1) Extend dw_email_connections with OAuth inbound fields; (2) per-FU monitor rules.

ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS direction VARCHAR(20) NOT NULL DEFAULT 'OUTBOUND';
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR(20);
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS oauth_refresh_token_encrypted TEXT;
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS oauth_access_token_encrypted TEXT;
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS token_expires_at TIMESTAMP;
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS mailbox_address VARCHAR(255);
ALTER TABLE dw_email_connections ADD COLUMN IF NOT EXISTS oauth_scopes TEXT;

-- host is NOT NULL for SMTP; inbound OAuth connections have no SMTP host. Relax to allow inbound rows.
ALTER TABLE dw_email_connections ALTER COLUMN host DROP NOT NULL;
ALTER TABLE dw_email_connections ALTER COLUMN from_email DROP NOT NULL;

CREATE TABLE IF NOT EXISTS dw_email_monitor_rules (
    id BIGSERIAL PRIMARY KEY,
    rule_uid VARCHAR(64) NOT NULL UNIQUE,
    function_unit_id BIGINT NOT NULL REFERENCES dw_function_units(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    connection_uid VARCHAR(64) NOT NULL,
    process_definition_key VARCHAR(255),
    start_event_id VARCHAR(255),
    folder_label VARCHAR(255) DEFAULT 'INBOX',
    filter_from VARCHAR(255),
    filter_subject VARCHAR(500),
    action_type VARCHAR(30) NOT NULL DEFAULT 'START_PROCESS',
    target_form_id BIGINT,
    target_binding_id VARCHAR(64),
    system_initiator_user_id VARCHAR(64),
    extraction_rules JSONB,
    correlation JSONB,
    poll_interval_seconds INTEGER NOT NULL DEFAULT 60,
    review_on_missing BOOLEAN DEFAULT TRUE,
    last_sync_cursor TEXT,
    last_synced_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(function_unit_id, name)
);

CREATE INDEX IF NOT EXISTS idx_dw_email_monitor_fu ON dw_email_monitor_rules(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_dw_email_monitor_uid ON dw_email_monitor_rules(rule_uid);

COMMENT ON TABLE dw_email_monitor_rules IS 'Per–Function Unit inbound email monitor rules: trigger + no-code extraction rules (design-time source)';
COMMENT ON COLUMN dw_email_monitor_rules.extraction_rules IS 'Visual-pick/AI-assist extractionRules JSON consumed by the workflow-engine interpreter';


-- =============================================================================
-- 49-sys-email-monitor-rules.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\49-sys-email-monitor-rules.sql
-- =============================================================================
-- Align with Flyway V3 (admin-center): runtime-synced inbound email monitor rules.
-- (1) Extend sys_email_connections with OAuth inbound fields; (2) synced monitor rules.

ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS direction VARCHAR(20) NOT NULL DEFAULT 'OUTBOUND';
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR(20);
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS oauth_refresh_token_encrypted TEXT;
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS oauth_access_token_encrypted TEXT;
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS token_expires_at TIMESTAMP;
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS mailbox_address VARCHAR(255);
ALTER TABLE sys_email_connections ADD COLUMN IF NOT EXISTS oauth_scopes TEXT;

ALTER TABLE sys_email_connections ALTER COLUMN host DROP NOT NULL;
ALTER TABLE sys_email_connections ALTER COLUMN from_email DROP NOT NULL;

CREATE TABLE IF NOT EXISTS sys_email_monitor_rules (
    id VARCHAR(64) PRIMARY KEY,
    function_unit_id VARCHAR(64) NOT NULL REFERENCES sys_function_units(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    connection_uid VARCHAR(64) NOT NULL,
    process_definition_key VARCHAR(255),
    start_event_id VARCHAR(255),
    folder_label VARCHAR(255) DEFAULT 'INBOX',
    filter_from VARCHAR(255),
    filter_subject VARCHAR(500),
    action_type VARCHAR(30) NOT NULL DEFAULT 'START_PROCESS',
    target_form_id VARCHAR(64),
    target_binding_id VARCHAR(64),
    system_initiator_user_id VARCHAR(64),
    extraction_rules JSONB,
    correlation JSONB,
    poll_interval_seconds INTEGER NOT NULL DEFAULT 60,
    review_on_missing BOOLEAN DEFAULT TRUE,
    last_sync_cursor TEXT,
    last_synced_at TIMESTAMP,
    synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(function_unit_id, name)
);

CREATE INDEX IF NOT EXISTS idx_sys_email_monitor_fu ON sys_email_monitor_rules(function_unit_id);
CREATE INDEX IF NOT EXISTS idx_sys_email_monitor_enabled ON sys_email_monitor_rules(enabled);

COMMENT ON TABLE sys_email_monitor_rules IS 'Inbound email monitor rules synced from developer workstation on Function Unit import/deploy';


-- =============================================================================
-- 50-we-email-inbound.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\50-we-email-inbound.sql
-- =============================================================================
-- Workflow-engine inbound email bookkeeping (engine has ddl-auto=none + no Flyway; init-scripts authoritative).
-- Idempotency ledger: one row per (rule_uid, message_id) ensures an email triggers a process at most once.

CREATE TABLE IF NOT EXISTS we_email_processed_messages (
    id BIGSERIAL PRIMARY KEY,
    rule_uid VARCHAR(64) NOT NULL,
    message_id VARCHAR(512) NOT NULL,
    process_instance_id VARCHAR(64),
    status VARCHAR(30) NOT NULL DEFAULT 'STARTED',
    error_message TEXT,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(rule_uid, message_id)
);

CREATE INDEX IF NOT EXISTS idx_we_email_processed_rule ON we_email_processed_messages(rule_uid);
CREATE INDEX IF NOT EXISTS idx_we_email_processed_status ON we_email_processed_messages(status);

COMMENT ON TABLE we_email_processed_messages IS 'Idempotency + audit ledger for inbound emails consumed by the monitor scheduler (STARTED/REVIEW/FAILED)';


-- =============================================================================
-- 51-add-login-platform-to-audit.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\51-add-login-platform-to-audit.sql
-- =============================================================================
-- =====================================================
-- Add login_platform column to sys_login_audit
-- Supports tracking which app (ADMIN_CENTER/USER_PORTAL/DEVELOPER_WORKSTATION) the login event originated from.
-- =====================================================
ALTER TABLE sys_login_audit ADD COLUMN IF NOT EXISTS login_platform VARCHAR(32);


-- =============================================================================
-- 51-dw-main-table-view-access.sql
-- Source file: deploy\k8s\init-data\init-platform-schema\51-dw-main-table-view-access.sql
-- =============================================================================
-- Main Table View access control (BU + Role visibility, data involvement flag)

ALTER TABLE dw_main_table_view_configs
    ADD COLUMN IF NOT EXISTS restrict_to_involved_users BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN dw_main_table_view_configs.restrict_to_involved_users IS
    'When true, portal row data is limited to users involved in each process (initiator, assignee, MI participant).';

CREATE TABLE IF NOT EXISTS dw_main_table_view_access (
    id              BIGSERIAL PRIMARY KEY,
    view_config_id  BIGINT NOT NULL REFERENCES dw_main_table_view_configs(id) ON DELETE CASCADE,
    target_type     VARCHAR(20) NOT NULL,
    target_id       VARCHAR(64) NOT NULL,
    CONSTRAINT chk_mtv_access_target_type CHECK (target_type IN ('ROLE', 'BUSINESS_UNIT'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_mtv_access_view_target
    ON dw_main_table_view_access(view_config_id, target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_mtv_access_view_config
    ON dw_main_table_view_access(view_config_id);

COMMENT ON TABLE dw_main_table_view_access IS 'Per-view BU/Role visibility rules for User Portal main-table views';



-- =============================================================================
-- 54-up-record-notes.sql
-- Source file: deploy/init-scripts/00-schema/54-up-record-notes.sql
-- =============================================================================
CREATE TABLE IF NOT EXISTS up_record_note (
    id                VARCHAR(64) PRIMARY KEY,
    target_type       VARCHAR(20)  NOT NULL,
    target_id         VARCHAR(64)  NOT NULL,
    table_kind        VARCHAR(10)  NOT NULL DEFAULT 'DW',
    table_id          VARCHAR(64)  NOT NULL,
    function_unit_id  VARCHAR(64),

    note_type         VARCHAR(20)  NOT NULL DEFAULT 'COMMENT',
    parent_note_id    VARCHAR(64)  REFERENCES up_record_note(id) ON DELETE CASCADE,

    subject           VARCHAR(255),
    body_html         TEXT,
    body_text         TEXT,

    file_name         VARCHAR(255),
    mime_type         VARCHAR(255),
    file_size         BIGINT,
    file_content      BYTEA,
    is_inline_image   BOOLEAN      NOT NULL DEFAULT FALSE,

    created_by        VARCHAR(64)  NOT NULL,
    created_by_name   VARCHAR(100),
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(64),
    updated_at        TIMESTAMP(6),
    is_deleted        BOOLEAN      NOT NULL DEFAULT FALSE,
    lock_version      BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_record_note_target_type CHECK (target_type IN ('TABLE', 'RECORD')),
    CONSTRAINT chk_record_note_table_kind CHECK (table_kind IN ('DW', 'RT')),
    CONSTRAINT chk_record_note_shape CHECK (
        (note_type = 'COMMENT'    AND body_html IS NOT NULL)
     OR (note_type = 'ATTACHMENT' AND file_name IS NOT NULL AND file_content IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_up_record_note_target
    ON up_record_note (target_type, target_id, created_at DESC)
    WHERE is_deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_up_record_note_parent
    ON up_record_note (parent_note_id);

CREATE INDEX IF NOT EXISTS idx_up_record_note_fu
    ON up_record_note (function_unit_id);

COMMENT ON TABLE up_record_note IS 'Record-level notes: rich-text comments and file attachments (Dataverse annotation-style single table)';
COMMENT ON COLUMN up_record_note.target_type IS 'Stream key = (target_type, target_id, table_id), never crossing process instances. TABLE = per-instance table stream (target_id = process instance id); RECORD = single row notes (target_id = row PK)';
COMMENT ON COLUMN up_record_note.table_kind IS 'ID space of table_id: DW = dw_table_definitions, RT = rt_table_definitions';
COMMENT ON COLUMN up_record_note.note_type IS 'COMMENT = rich-text note; ATTACHMENT = binary file (parent_note_id links it to its comment, NULL for standalone uploads)';
COMMENT ON COLUMN up_record_note.is_inline_image IS 'TRUE when the attachment is an inline image referenced from a comment body, hidden from the attachment list';
COMMENT ON COLUMN up_record_note.body_text IS 'Plain-text extraction of body_html for list summaries and search';


-- =============================================================================
-- 55-dw-main-table-view-lookup-display-columns.sql
-- Source file: deploy/init-scripts/00-schema/55-dw-main-table-view-lookup-display-columns.sql
-- =============================================================================
-- Main Table View: Power Apps-style lookup display columns
-- column_type = 'field' (default) | 'lookup_display'
-- lookup_display rows store source field (e.g. t) + target attribute (e.g. full_name);
-- field_name uses synthetic key source@display (e.g. t@full_name).

ALTER TABLE dw_main_table_view_fields
    ADD COLUMN IF NOT EXISTS column_type VARCHAR(20) NOT NULL DEFAULT 'field';

ALTER TABLE dw_main_table_view_fields
    ADD COLUMN IF NOT EXISTS lookup_source_field VARCHAR(100);

ALTER TABLE dw_main_table_view_fields
    ADD COLUMN IF NOT EXISTS lookup_display_field VARCHAR(100);

COMMENT ON COLUMN dw_main_table_view_fields.column_type IS
    'field = physical/system column; lookup_display = derived attribute from a lookup source field';
COMMENT ON COLUMN dw_main_table_view_fields.lookup_source_field IS
    'For lookup_display: form/main-table lookup field name (e.g. t)';
COMMENT ON COLUMN dw_main_table_view_fields.lookup_display_field IS
    'For lookup_display: attribute on the lookup target table (e.g. full_name)';


-- =============================================================================
-- 56-dw-user-preferences.sql
-- Source file: deploy/init-scripts/00-schema/56-dw-user-preferences.sql
-- =============================================================================
CREATE TABLE IF NOT EXISTS dw_user_preferences (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    pref_key VARCHAR(64) NOT NULL,
    pref_value TEXT NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dw_user_pref UNIQUE (user_id, pref_key)
);

COMMENT ON TABLE dw_user_preferences IS 'Per-user UI preferences for developer workstation (e.g. launchpad layout)';

-- =============================================================================
-- 18-add-read-only-to-form-stage-bindings.sql
-- Source file: deploy/init-scripts/00-schema/18-add-read-only-to-form-stage-bindings.sql
-- =============================================================================
-- Add read_only column to dw_form_stage_bindings
-- This column stores the form node binding's readonly flag
-- so the user portal can determine form editability independently of BPMN

ALTER TABLE dw_form_stage_bindings 
ADD COLUMN IF NOT EXISTS read_only BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN dw_form_stage_bindings.read_only IS 'Whether the form bound to this stage is read-only';

-- =============================================================================
-- 42-add-dw-binding-link-mode.sql
-- Source file: deploy/init-scripts/00-schema/42-add-dw-binding-link-mode.sql
-- =============================================================================
\set ON_ERROR_STOP on

-- =====================================================
-- Developer Workstation: Add binding_link_mode column
-- =====================================================
-- The column is already in 04-developer-workstation-schema.sql base DDL
-- but older databases initialized before the column was added to the base
-- schema are missing it.  This script backfills the column for those
-- databases so that cascading JPA operations (delete, list) on
-- dw_form_table_bindings do not fail with "column does not exist".
--
-- Keep idempotent via IF NOT EXISTS.
-- =====================================================

ALTER TABLE dw_form_table_bindings
  ADD COLUMN IF NOT EXISTS binding_link_mode VARCHAR(32) NOT NULL DEFAULT 'structuralFk';

COMMENT ON COLUMN dw_form_table_bindings.binding_link_mode IS
  'Link mode: structuralFk (FK via table structure) or customField (user-managed FK field)';

-- =============================================================================
-- 43-add-dw-field-fk-pk-metadata.sql
-- Source file: deploy/init-scripts/00-schema/43-add-dw-field-fk-pk-metadata.sql
-- =============================================================================
\set ON_ERROR_STOP on

-- =====================================================
-- Developer Workstation: Add FieldDefinition FK/PK metadata columns
-- =====================================================
-- These columns exist in 04-developer-workstation-schema.sql base DDL but were
-- added later (Flyway V317 migration).  Older databases initialized before the
-- columns were added to the base DDL are missing them, causing cascading JPA
-- operations (delete, list) on dw_field_definitions to fail with
-- "column does not exist".
--
-- Keep idempotent via ADD COLUMN IF NOT EXISTS.
-- =====================================================

ALTER TABLE dw_field_definitions
    ADD COLUMN IF NOT EXISTS is_foreign_key        BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ref_table_id           BIGINT,
    ADD COLUMN IF NOT EXISTS ref_primary_key_fields JSONB,
    ADD COLUMN IF NOT EXISTS pk_generation_json     JSONB,
    ADD COLUMN IF NOT EXISTS fk_display_mode        VARCHAR(20) DEFAULT 'readonly',
    ADD COLUMN IF NOT EXISTS relation_cardinality   VARCHAR(20);

COMMENT ON COLUMN dw_field_definitions.is_foreign_key IS
    'Whether this column is a foreign key reference to another table';
COMMENT ON COLUMN dw_field_definitions.ref_table_id IS
    'Target table ID when is_foreign_key = true';
COMMENT ON COLUMN dw_field_definitions.ref_primary_key_fields IS
    'List of primary-key fields in the referenced table (JSONB)';
COMMENT ON COLUMN dw_field_definitions.pk_generation_json IS
    'Primary key generation configuration (JSONB)';
COMMENT ON COLUMN dw_field_definitions.fk_display_mode IS
    'FK display mode: readonly, select, search, etc.';
COMMENT ON COLUMN dw_field_definitions.relation_cardinality IS
    'Relationship cardinality: ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY';

-- =============================================================================
-- 52-add-rt-lookup-config.sql
-- Source file: deploy/init-scripts/00-schema/52-add-rt-lookup-config.sql
-- =============================================================================
\set ON_ERROR_STOP on

-- =====================================================
-- Relation Tables: Add per-column LOOKUP field config
-- =====================================================
-- Adds a JSONB `lookup_config` column to rt_field_definitions so a Relation
-- Table column can be typed as LOOKUP (references another Relation Table).
-- The config carries: refTableId, searchFields, displayFields,
-- selectedDisplayField, filterConditions, showBackfillView, multiple, and the
-- derivedFrom block (parentField + join columns) that drives derived
-- auto-fill / cascade filtering between two lookup columns.
--
-- Keep idempotent via ADD COLUMN IF NOT EXISTS.
-- =====================================================

ALTER TABLE rt_field_definitions
    ADD COLUMN IF NOT EXISTS lookup_config JSONB;

COMMENT ON COLUMN rt_field_definitions.lookup_config IS
    'LOOKUP field configuration (JSONB): refTableId, searchFields, displayFields, '
    'selectedDisplayField, filterConditions, showBackfillView, multiple, derivedFrom '
    '{parentField, joins[{fromColumn,toColumn,matchType}], derivedMode}. '
    'Only meaningful when data_type = LOOKUP.';

-- =============================================================================
-- 53-dw-process-definitions-fu-unique.sql
-- Source file: deploy/init-scripts/00-schema/53-dw-process-definitions-fu-unique.sql
-- =============================================================================
\set ON_ERROR_STOP on

-- =====================================================
-- Developer Workstation: one process definition per FU
-- =====================================================
-- Enforce at most one dw_process_definitions row per
-- function_unit_id. Idempotent via IF NOT EXISTS.
-- =====================================================

CREATE UNIQUE INDEX IF NOT EXISTS idx_dw_process_definitions_fu_unique
ON dw_process_definitions(function_unit_id);

COMMENT ON INDEX idx_dw_process_definitions_fu_unique IS
    'Ensure at most one process definition per function unit';

-- =============================================================================
-- 57-smtp-system-config.sql
-- Source file: deploy/init-scripts/00-schema/57-smtp-system-config.sql
-- =============================================================================
-- Global SMTP system config keys for Admin Center System Parameters.
-- Idempotent seed + migrate legacy smtp.server → smtp.host when host is empty.

INSERT INTO admin_system_configs (
    id, category, config_key, config_name, config_value, default_value,
    value_type, description, encrypted, editable, version, environment
) VALUES
    (gen_random_uuid()::text, 'SYSTEM', 'session.timeout', 'Session Timeout', '30', '30',
     'INTEGER', 'Session timeout in minutes', FALSE, TRUE, 1, 'DEV'),
    (gen_random_uuid()::text, 'SYSTEM', 'file.maxSize', 'File Upload Limit', '10', '10',
     'INTEGER', 'File upload limit in MB', FALSE, TRUE, 1, 'DEV'),
    (gen_random_uuid()::text, 'SYSTEM', 'smtp.host', 'SMTP Host', '', '',
     'STRING', 'Global SMTP host for outbound email connections', FALSE, TRUE, 1, 'DEV'),
    (gen_random_uuid()::text, 'SYSTEM', 'smtp.port', 'SMTP Port', '25', '25',
     'INTEGER', 'Global SMTP port for outbound email connections', FALSE, TRUE, 1, 'DEV'),
    (gen_random_uuid()::text, 'SYSTEM', 'smtp.useTls', 'SMTP Use TLS', 'true', 'true',
     'BOOLEAN', 'Global SMTP TLS for outbound email connections', FALSE, TRUE, 1, 'DEV'),
    (gen_random_uuid()::text, 'BUSINESS', 'process.timeout', 'Process Timeout', '7', '7',
     'INTEGER', 'Process timeout in days', FALSE, TRUE, 1, 'DEV'),
    (gen_random_uuid()::text, 'BUSINESS', 'task.assignRule', 'Task Assignment Rule', 'ROUND_ROBIN', 'ROUND_ROBIN',
     'STRING', 'Default task assignment rule', FALSE, TRUE, 1, 'DEV')
ON CONFLICT (config_key) DO NOTHING;

-- Copy legacy Mail Server value into smtp.host when host is still empty.
UPDATE admin_system_configs AS host_cfg
SET config_value = legacy.config_value,
    updated_at = CURRENT_TIMESTAMP
FROM admin_system_configs AS legacy
WHERE host_cfg.config_key = 'smtp.host'
  AND legacy.config_key = 'smtp.server'
  AND (host_cfg.config_value IS NULL OR btrim(host_cfg.config_value) = '')
  AND legacy.config_value IS NOT NULL
  AND btrim(legacy.config_value) <> '';

-- =============================================================================
-- 58-widen-wf-assignment-target-to-text.sql
-- Source file: deploy/init-scripts/00-schema/58-widen-wf-assignment-target-to-text.sql
-- =============================================================================
-- =============================================================================
-- Widen wf_extended_task_info.assignment_target VARCHAR(255) → TEXT
-- =============================================================================
-- Symptom: Task creation fails when role/BU resolution yields many candidate
--   user IDs joined into assignment_target (comma-separated) exceeding 255 chars.
-- Cause: Column was VARCHAR(255); large CANDIDATE_USERS pools overflow.
-- Fix:
--   1) ALTER column to TEXT
--   2) Drop btree index on the full column (low value for long candidate strings;
--      task claiming uses Flowable identity links)
--   3) Recreate partial index for non-CANDIDATE_USERS rows only (USER / VIRTUAL_GROUP /
--      DEPT_ROLE still benefit from equality lookups)
--
-- Idempotent for existing and fresh databases.
-- =============================================================================

DROP INDEX IF EXISTS idx_assignment_target;

ALTER TABLE IF EXISTS wf_extended_task_info
    ALTER COLUMN assignment_target TYPE TEXT;

DROP INDEX IF EXISTS idx_assignment_target_non_candidate_users;

CREATE INDEX IF NOT EXISTS idx_assignment_target_non_candidate_users
    ON wf_extended_task_info (assignment_target)
    WHERE assignment_type <> 'CANDIDATE_USERS';

COMMENT ON COLUMN wf_extended_task_info.assignment_target IS
    'Assignment target: user/group/dept-role id, or comma-separated candidate user ids (TEXT for large pools)';

COMMENT ON INDEX idx_assignment_target_non_candidate_users IS
    'Equality lookup for non-CANDIDATE_USERS assignment targets; excludes long candidate-user pools';

-- =============================================================================
-- 59-add-up-change-history-indexes.sql
-- Source file: deploy/init-scripts/00-schema/59-add-up-change-history-indexes.sql
-- =============================================================================
-- =====================================================
-- User Portal: add standalone indexes on up_change_history
-- for global cross-process audit log queries
-- =====================================================

CREATE INDEX IF NOT EXISTS idx_change_history_user_id ON up_change_history(user_id);
CREATE INDEX IF NOT EXISTS idx_change_history_timestamp_standalone ON up_change_history(timestamp);

COMMENT ON INDEX idx_change_history_user_id IS 'Supports global audit query filtered by user';
COMMENT ON INDEX idx_change_history_timestamp_standalone IS 'Supports global audit query filtered/sorted by time range';

-- =============================================================================
-- 60-add-user-avatar.sql
-- Source file: deploy/init-scripts/00-schema/60-add-user-avatar.sql
-- =============================================================================
-- Add avatar column to sys_users for storing LDAP jpegPhoto
ALTER TABLE sys_users ADD COLUMN IF NOT EXISTS avatar BYTEA;

-- =============================================================================
-- 63-virtual-group-type-developer.sql
-- Source file: deploy/init-scripts/00-schema/63-virtual-group-type-developer.sql
-- =============================================================================
-- Allow virtual group type DEVELOPER alongside SYSTEM and CUSTOM.
-- Existing rows keep their type; no data migration.
-- (Also reflected in CREATE TABLE chk_virtual_group_type above for fresh installs.)

ALTER TABLE sys_virtual_groups DROP CONSTRAINT IF EXISTS chk_virtual_group_type;

ALTER TABLE sys_virtual_groups
    ADD CONSTRAINT chk_virtual_group_type
    CHECK (type IN ('SYSTEM', 'CUSTOM', 'DEVELOPER'));

COMMENT ON COLUMN sys_virtual_groups.type IS
    'SYSTEM (built-in, non-deletable), CUSTOM (business/task-pool), DEVELOPER (DW team)';

-- =============================================================================
-- 66-add-auditor-role-type.sql
-- Source file: deploy/init-scripts/00-schema/66-add-auditor-role-type.sql
-- =============================================================================
ALTER TABLE sys_roles DROP CONSTRAINT IF EXISTS chk_role_type;

ALTER TABLE sys_roles
    ADD CONSTRAINT chk_role_type
    CHECK (type IN ('ADMIN', 'AUDITOR', 'DEVELOPER', 'BU_BOUNDED', 'BU_UNBOUNDED'));

COMMENT ON COLUMN sys_roles.type IS
    'ADMIN (system administration), AUDITOR (read-only audit), DEVELOPER (design studio), BU_BOUNDED / BU_UNBOUNDED (business roles)';

-- =============================================================================
-- 68-form-scene-and-fu-audit-access.sql
-- Source file: deploy/init-scripts/00-schema/68-form-scene-and-fu-audit-access.sql
-- =============================================================================
-- Splits "which form renders this step" by scene so My Requests can be designed
-- independently of To Do, and adds a FU-level audit grant so reviewers who are
-- neither initiator nor participant can see every request of a function unit.

-- --- 1. scene axis on per-node form bindings -----------------
ALTER TABLE dw_form_stage_bindings
    ADD COLUMN IF NOT EXISTS scene VARCHAR(16) NOT NULL DEFAULT 'TASK';

ALTER TABLE dw_form_stage_bindings
    DROP CONSTRAINT IF EXISTS chk_form_stage_binding_scene;
ALTER TABLE dw_form_stage_bindings
    ADD CONSTRAINT chk_form_stage_binding_scene CHECK (scene IN ('TASK', 'REQUEST'));

-- The original UNIQUE(form_id, stage_id) was declared inline, so Postgres
-- auto-named it. It must be dropped explicitly: a plain CREATE ... IF NOT
-- EXISTS never replaces an existing constraint, and while it survives, one
-- node cannot hold both a TASK and a REQUEST form — the whole point here.
ALTER TABLE dw_form_stage_bindings
    DROP CONSTRAINT IF EXISTS dw_form_stage_bindings_form_id_stage_id_key;
ALTER TABLE dw_form_stage_bindings
    DROP CONSTRAINT IF EXISTS uk_form_stage_binding_form_stage_scene;
ALTER TABLE dw_form_stage_bindings
    ADD CONSTRAINT uk_form_stage_binding_form_stage_scene UNIQUE (form_id, stage_id, scene);

-- --- 2. scene axis on form definitions -----------------------
-- Carries the REQUEST variant of the PROCESS form. The start step is not a
-- BPMN node, so it has no stage id to bind to; a synthetic stage id would
-- collide with node-id lookups and validation.
ALTER TABLE dw_form_definitions
    ADD COLUMN IF NOT EXISTS scene VARCHAR(16) NOT NULL DEFAULT 'TASK';

ALTER TABLE dw_form_definitions
    DROP CONSTRAINT IF EXISTS chk_form_definition_scene;
ALTER TABLE dw_form_definitions
    ADD CONSTRAINT chk_form_definition_scene CHECK (scene IN ('TASK', 'REQUEST'));

-- --- 3. DETAIL form type + per-view detail form --------------
-- The constraint is named chk_form_type in 04-developer-workstation-schema.sql;
-- the auto-generated name is dropped too in case some environment carries it.
ALTER TABLE dw_form_definitions
    DROP CONSTRAINT IF EXISTS chk_form_type;
ALTER TABLE dw_form_definitions
    DROP CONSTRAINT IF EXISTS dw_form_definitions_form_type_check;
ALTER TABLE dw_form_definitions
    ADD CONSTRAINT chk_form_type
    CHECK (form_type IN ('PROCESS', 'TASK', 'ACTION', 'DETAIL'));

ALTER TABLE dw_main_table_view_configs
    ADD COLUMN IF NOT EXISTS detail_form_id BIGINT;

ALTER TABLE dw_main_table_view_configs
    DROP CONSTRAINT IF EXISTS fk_mtv_configs_detail_form;
ALTER TABLE dw_main_table_view_configs
    ADD CONSTRAINT fk_mtv_configs_detail_form
    FOREIGN KEY (detail_form_id) REFERENCES dw_form_definitions(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_mtv_configs_detail_form
    ON dw_main_table_view_configs(detail_form_id)
    WHERE detail_form_id IS NOT NULL;

-- --- 4. FU-level audit grant ---------------------------------
-- Deliberately a separate table rather than a new access_type on
-- sys_function_unit_access: every reader of that table filters on
-- target_type only and ignores access_type, so an AUDIT row there would be
-- read as a launch grant. A separate key space cannot leak that way.
CREATE TABLE IF NOT EXISTS sys_function_unit_audit_access (
    id VARCHAR(64) PRIMARY KEY,
    function_unit_id VARCHAR(64) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    CONSTRAINT fk_audit_access_func_unit FOREIGN KEY (function_unit_id)
        REFERENCES sys_function_units(id) ON DELETE CASCADE,
    CONSTRAINT chk_audit_access_target_type CHECK (target_type IN ('ROLE'))
);

CREATE INDEX IF NOT EXISTS idx_fu_audit_access_func_unit
    ON sys_function_unit_audit_access(function_unit_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_fu_audit_access_target
    ON sys_function_unit_audit_access(function_unit_id, target_type, target_id);

COMMENT ON TABLE sys_function_unit_audit_access IS
    'Roles allowed to review every request of a function unit without being initiator or participant.';
COMMENT ON COLUMN dw_form_stage_bindings.scene IS
    'TASK = To Do/Completed rendering; REQUEST = My Requests/audit rendering.';
COMMENT ON COLUMN dw_form_definitions.scene IS
    'TASK = To Do/Completed rendering; REQUEST = My Requests/audit rendering.';
COMMENT ON COLUMN dw_main_table_view_configs.detail_form_id IS
    'DETAIL form rendered when a row of this view is opened; NULL = no detail page.';

-- =============================================================================
-- 69-add-action-binding-type.sql
-- Source file: deploy/init-scripts/00-schema/69-add-action-binding-type.sql
-- =============================================================================
-- Allow ACTION-type tables to be bound to forms (dw_form_table_bindings.binding_type),
-- so FORM_POPUP action forms (e.g. Meeting Remark) can bind their own dw_table_definitions
-- row (table_type='ACTION') the same way SUB tables bind to PROCESS/TASK forms.
ALTER TABLE dw_form_table_bindings DROP CONSTRAINT IF EXISTS chk_binding_type;
ALTER TABLE dw_form_table_bindings ADD CONSTRAINT chk_binding_type
    CHECK (binding_type IN ('PRIMARY', 'SUB', 'RELATED', 'ACTION'));

-- =============================================================================
-- 70-add-rt-function-unit.sql
-- Source file: deploy/init-scripts/00-schema/70-add-rt-function-unit.sql
-- =============================================================================
-- Allow Relation Tables to be optionally grouped under a Function Unit,
-- so admin-center Table Structure / Table Data and user-portal Relation Tables
-- can group/filter the (growing) flat table list by Function Unit.
-- NULL = ungrouped; deleting the Function Unit does not delete the relation table.
ALTER TABLE rt_table_definitions ADD COLUMN IF NOT EXISTS function_unit_id VARCHAR(64);

ALTER TABLE rt_table_definitions DROP CONSTRAINT IF EXISTS fk_rt_table_function_unit;
ALTER TABLE rt_table_definitions ADD CONSTRAINT fk_rt_table_function_unit
    FOREIGN KEY (function_unit_id) REFERENCES sys_function_units(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_rt_table_function_unit ON rt_table_definitions(function_unit_id);

COMMENT ON COLUMN rt_table_definitions.function_unit_id IS 'Optional Function Unit grouping (sys_function_units.id); NULL = ungrouped';

-- =============================================================================
-- 71-list-pagination-indexes.sql
-- Source file: deploy/init-scripts/00-schema/71-list-pagination-indexes.sql
-- =============================================================================
-- Pagination indexes for Completed Tasks (ACT_HI_TASKINST by assignee + end time)
-- and My Requests (up_process_instance by initiator + status + start time).
-- Partial index on historic tasks matches WHERE END_TIME_ IS NOT NULL.

CREATE INDEX IF NOT EXISTS idx_hi_taskinst_assignee_end
    ON act_hi_taskinst (assignee_, end_time_ DESC)
    WHERE end_time_ IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_up_pi_user_status_start
    ON up_process_instance (start_user_id, status, start_time DESC);

-- =============================================================================
-- 72-add-rt-table-function-units.sql
-- Source file: deploy/init-scripts/00-schema/72-add-rt-table-function-units.sql
-- =============================================================================
-- Relation Table <-> Function Unit becomes many-to-many: a table may be linked to multiple
-- Function Units, or to none (Common — visible/usable across all Function Units).
-- rt_table_definitions.function_unit_id (single FK, see 70-add-rt-function-unit.sql) is kept
-- as-is for backward compatibility but is no longer written by new code; this table is the
-- new source of truth going forward.
CREATE TABLE IF NOT EXISTS rt_table_function_units (
    id                  VARCHAR(64)  NOT NULL PRIMARY KEY,
    relation_table_id   BIGINT       NOT NULL REFERENCES rt_table_definitions(id) ON DELETE CASCADE,
    function_unit_id    VARCHAR(64)  NOT NULL REFERENCES sys_function_units(id) ON DELETE CASCADE,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    CONSTRAINT uk_rt_table_fu UNIQUE (relation_table_id, function_unit_id)
);

CREATE INDEX IF NOT EXISTS idx_rt_table_fu_table ON rt_table_function_units(relation_table_id);
CREATE INDEX IF NOT EXISTS idx_rt_table_fu_fu ON rt_table_function_units(function_unit_id);

COMMENT ON TABLE rt_table_function_units IS 'Many-to-many link between rt_table_definitions and sys_function_units; a table with no rows here is Common (visible to all Function Units)';

-- One-time backfill: migrate any existing single-FK assignment into the new link table.
INSERT INTO rt_table_function_units (id, relation_table_id, function_unit_id, created_at, created_by)
SELECT gen_random_uuid()::text, t.id, t.function_unit_id, now(), 'system-migration'
FROM rt_table_definitions t
WHERE t.function_unit_id IS NOT NULL
ON CONFLICT (relation_table_id, function_unit_id) DO NOTHING;

-- =============================================================================
-- 80-rename-email-connection-credential.sql
-- Source file: deploy/init-scripts/00-schema/80-rename-email-connection-credential.sql
-- GUI / existing copies may still have password_encrypted; rename then ADD IF NOT EXISTS.
-- =============================================================================

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

