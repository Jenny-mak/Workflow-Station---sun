# =====================================================
# Database Initialization Script (Standalone)
# =====================================================
# Run with psql directly (not via Docker).
#
# Usage:
#   .\init-database.ps1 -DbHost localhost -DbPort 5432 -DbName workflow_platform_dev -DbUser platform_dev -DbPassword dev_password_123
# =====================================================

param(
    [string]$DbHost = "localhost",
    [int]$DbPort = 5432,
    [string]$DbName = "workflow_platform_dev",
    [string]$DbUser = "platform_dev",
    [string]$DbPassword = ""
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Write-Step { param([string]$Msg) Write-Host "`n[$((Get-Date).ToString('HH:mm:ss'))] $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "  OK: $Msg" -ForegroundColor Green }
function Write-Fail { param([string]$Msg) Write-Host "  FAIL: $Msg" -ForegroundColor Red }

function Exec-Sql {
    param([string]$File, [string]$Desc)
    if (-not (Test-Path $File)) { Write-Fail "$Desc - not found: $File"; return $false }
    $env:PGPASSWORD = $DbPassword
    try {
        & psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -f $File 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Ok $Desc; return $true }
        else { Write-Fail $Desc; return $false }
    } finally { $env:PGPASSWORD = "" }
}

# =====================================================
Write-Host ""
Write-Host "=========================================" -ForegroundColor Yellow
Write-Host "  Database Initialization" -ForegroundColor Yellow
Write-Host "=========================================" -ForegroundColor Yellow
Write-Host "  Host: ${DbHost}:${DbPort}"
Write-Host "  Database: $DbName"
Write-Host "  User: $DbUser"
Write-Host "=========================================" -ForegroundColor Yellow

try { $null = Get-Command psql -ErrorAction Stop }
catch { Write-Fail "psql not found."; exit 1 }

if ([string]::IsNullOrEmpty($DbPassword)) {
    Write-Host "  WARNING: No password provided. psql may prompt for password or use .pgpass file." -ForegroundColor Yellow
}

# Step 1: Base schemas
Write-Step "Step 1/6: Creating base schemas..."
$schemas = @(
    "00-schema/01-platform-security-schema.sql",
    "00-schema/02-workflow-engine-schema.sql",
    "00-schema/03-user-portal-schema.sql",
    "00-schema/04-developer-workstation-schema.sql",
    "00-schema/05-admin-center-schema.sql"
)
foreach ($s in $schemas) {
    if (-not (Exec-Sql -File (Join-Path $ScriptDir $s) -Desc (Split-Path $s -Leaf))) { exit 1 }
}

# Step 2: Incremental migrations
Write-Step "Step 2/6: Applying incremental migrations..."
$migrations = @(
    "00-schema/06-add-deployment-rollback-columns.sql",
    "00-schema/07-add-action-definitions-table.sql",
    "00-schema/08-add-function-unit-versioning.sql",
    "00-schema/10-add-approval-order-column.sql",
    "00-schema/11-add-unique-enabled-constraint.sql",
    "00-schema/12-add-enabled-field-to-dw-function-units.sql",
    "00-schema/13-add-notification-table.sql",
    "00-schema/15-bi-management-schema.sql",
    "00-schema/16-add-decision-and-relations-tables.sql",
    "00-schema/17-add-lock-version-to-user-portal-tables.sql",
    "00-schema/18-add-lock-version-to-form-definitions.sql",
    "00-schema/18-add-read-only-to-form-stage-bindings.sql",
    "00-schema/19-add-up-change-history.sql",
    "00-schema/20-add-members-table.sql",
    "00-schema/21-add-rt-relation-tables.sql",
    "00-schema/22-add-lock-version-to-sys-roles.sql",
    "00-schema/23-widen-up-process-instance-business-key.sql",
    "00-schema/24-add-multi-instance-execution-table.sql",
    "00-schema/25-add-row-version-to-sub-tables.sql",
    "00-schema/26-add-dw-deployment-jobs.sql",
    "00-schema/27-add-up-process-instance-catalog-pin.sql",
    "00-schema/28-dw-function-unit-dev-groups.sql",
    "00-schema/29-up-permission-request-submitted-by.sql",
    "00-schema/30-widen-flowable-identitylink-columns.sql",
    "00-schema/31-widen-flowable-act-hi-comment-columns.sql",
    "00-schema/32-add-dw-form-table-binding-subview-columns.sql",
    "00-schema/33-dw-sub-table-view-tables.sql",
    "00-schema/34-dw-link-form-components.sql",
    "00-schema/34-extend-function-unit-status-check.sql",
    "00-schema/35-drop-init-function-unit-status.sql",
    "00-schema/36-sys-function-units-description.sql",
    "00-schema/37-sys-action-definitions-description.sql",
    "00-schema/38-dw-main-table-view-tables.sql",
    "00-schema/39-ac-ldap-sync-audit.sql",
    "00-schema/39-add-request-id-config-to-table-definitions.sql",
    "00-schema/40-superset-schema.sql",
    "00-schema/41-dw-function-unit-tags.sql",
    "00-schema/42-add-dw-binding-link-mode.sql",
    "00-schema/43-add-dw-field-fk-pk-metadata.sql",
    "00-schema/44-ac-ldap-sync-audit.sql",
    "00-schema/45-dw-email-connections.sql",
    "00-schema/46-sys-email-connections.sql",
    "00-schema/47-dw-email-templates.sql",
    "00-schema/48-dw-email-monitor-rules.sql",
    "00-schema/49-sys-email-monitor-rules.sql",
    "00-schema/50-we-email-inbound.sql",
    "00-schema/51-dw-main-table-view-access.sql",
    "00-schema/52-add-rt-lookup-config.sql",
    "00-schema/53-dw-process-definitions-fu-unique.sql",
    "00-schema/54-up-record-notes.sql",
    "00-schema/55-dw-main-table-view-lookup-display-columns.sql",
    "00-schema/56-dw-user-preferences.sql",
    "00-schema/57-smtp-system-config.sql",
    "00-schema/58-widen-wf-assignment-target-to-text.sql",
    "00-schema/59-add-up-change-history-indexes.sql",
    "00-schema/60-add-user-avatar.sql",
    "00-schema/61-widen-chk-content-type-email-template.sql",
    "00-schema/62-dw-ai-prompt-templates.sql",
    "00-schema/62-imap-system-config.sql",
    "00-schema/63-dw-email-connection-name-by-direction.sql",
    "00-schema/63-virtual-group-type-developer.sql",
    "00-schema/64-email-monitor-template-binding.sql",
    "00-schema/65-add-dw-field-computed.sql",
    "00-schema/66-add-auditor-role-type.sql",
    "00-schema/66-add-rt-field-computed.sql",
    "00-schema/67-backfill-subtable-row-id.sql",
    "00-schema/68-form-scene-and-fu-audit-access.sql",
    "00-schema/69-add-action-binding-type.sql",
    "00-schema/70-add-rt-function-unit.sql",
    "00-schema/71-list-pagination-indexes.sql",
    "00-schema/72-add-rt-table-function-units.sql",
    "00-schema/73-unique-table-name-case-insensitive.sql",
    "00-schema/74-ubr-membership-type.sql",
    "00-schema/74-widen-chk-content-type-decision-relation.sql",
    "00-schema/74-wf-extended-task-delegate-target.sql",
    "00-schema/75-up-user-preference-auto-claim.sql",
    "00-schema/76-up-user-preference-auto-preview.sql",
    "00-schema/77-dw-table-data-rows.sql",
    "00-schema/78-dw-icons-name-not-unique.sql",
    "00-schema/80-rename-email-connection-credential.sql"
)
foreach ($m in $migrations) {
    $path = Join-Path $ScriptDir $m
    if (-not (Test-Path $path)) { Write-Fail "Missing: $m"; exit 1 }
    Exec-Sql -File $path -Desc (Split-Path $m -Leaf) | Out-Null
}

# Step 3: Roles, groups, admin user
Write-Step "Step 3/6: Creating roles, groups, and admin user..."
Exec-Sql -File (Join-Path $ScriptDir "01-admin/01-create-roles-and-groups.sql") -Desc "Roles and virtual groups" | Out-Null
Exec-Sql -File (Join-Path $ScriptDir "01-admin/01-create-admin-only.sql") -Desc "Admin user" | Out-Null
Exec-Sql -File (Join-Path $ScriptDir "01-admin/02-init-developer-permissions.sql") -Desc "Developer permissions" | Out-Null
Exec-Sql -File (Join-Path $ScriptDir "01-admin/03-sync-role-tables.sql") -Desc "Sync role tables" | Out-Null
Exec-Sql -File (Join-Path $ScriptDir "01-admin/04-admin-permissions.sql") -Desc "Admin permissions" | Out-Null
Exec-Sql -File (Join-Path $ScriptDir "01-admin/05-e2e-test-users-and-business-units.sql") -Desc "E2E business units and users" | Out-Null
Exec-Sql -File (Join-Path $ScriptDir "01-admin/06-hase-organization-seed.sql") -Desc "HASE organization + HMDC roles" | Out-Null
Exec-Sql -File (Join-Path $ScriptDir "01-admin/10-add-auditor-role-type.sql") -Desc "AUDITOR role type + DW view clamp" | Out-Null

# Step 4: Wipe all function units (matches Docker init path)
Write-Step "Step 4/6: Wiping all function units (developer + deployed catalog)..."
$wipePath = Join-Path $ScriptDir "99-maintenance/00-wipe-all-function-units.sql"
if (Test-Path $wipePath) {
    if (-not (Exec-Sql -File $wipePath -Desc "Wipe function units")) { exit 1 }
} else {
    Write-Host "  SKIP: wipe script not found at $wipePath" -ForegroundColor Yellow
}

# Step 5: Demo function units — Platform Showcase (full-stack) then Lending then Meeting
Write-Step "Step 5a/6: Loading Platform Showcase (company promo demo)..."
$showcaseScripts = @(
    "15-platform-showcase/00-create-function-unit.sql",
    "15-platform-showcase/01-create-tables.sql",
    "15-platform-showcase/02-create-bpmn-process.sql",
    "15-platform-showcase/03-form-table-bindings.sql",
    "15-platform-showcase/04-table-relations.sql",
    "15-platform-showcase/05-form-stage-bindings.sql"
)
foreach ($f in $showcaseScripts) {
    $path = Join-Path $ScriptDir $f
    if (-not (Test-Path $path)) { Write-Fail "Missing: $f"; exit 1 }
    if (-not (Exec-Sql -File $path -Desc (Split-Path $f -Leaf))) { exit 1 }
}

Write-Step "Step 5b/6: Loading Digital Lending V2 EN..."
$fuScripts = @(
    "08-digital-lending-v2-en/00-create-virtual-groups.sql",
    "08-digital-lending-v2-en/01-create-digital-lending-complete.sql",
    "08-digital-lending-v2-en/02-insert-bpmn-process.sql",
    "08-digital-lending-v2-en/03-bind-actions.sql",
    "08-digital-lending-v2-en/04-merge-loan-application-subforms.sql"
)
foreach ($f in $fuScripts) {
    $path = Join-Path $ScriptDir $f
    if (Test-Path $path) {
        if (-not (Exec-Sql -File $path -Desc (Split-Path $f -Leaf))) { exit 1 }
    }
}
$e2eVg = Join-Path $ScriptDir "08-digital-lending-v2-en/05-e2e-virtual-group-members.sql"
if (Test-Path $e2eVg) {
    if (-not (Exec-Sql -File $e2eVg -Desc "E2E virtual group members (lending)")) { exit 1 }
}

Write-Step "Step 5c/6: Loading Meeting Participant Info Collection..."
$meetingParticipantScripts = @(
    "16-meeting-participant-collection/00-create-function-unit.sql",
    "16-meeting-participant-collection/01-create-tables.sql",
    "16-meeting-participant-collection/02-create-bpmn-process.sql",
    "16-meeting-participant-collection/03-form-table-bindings.sql",
    "16-meeting-participant-collection/04-update-bpmn-diagram.sql",
    "16-meeting-participant-collection/05-form-stage-bindings.sql",
    "16-meeting-participant-collection/06-create-physical-tables.sql",
    "16-meeting-participant-collection/06-translate-to-english.sql"
)
foreach ($f in $meetingParticipantScripts) {
    $path = Join-Path $ScriptDir $f
    if (-not (Test-Path $path)) { Write-Fail "Missing: $f"; exit 1 }
    if (-not (Exec-Sql -File $path -Desc (Split-Path $f -Leaf))) { exit 1 }
}

Write-Step "Step 5d/6: Loading Function Unit Multi-Instance Subtask Demo..."
$miSubtaskDemoInit = Join-Path $ScriptDir "17-Multi-Instance-Subtask-Demo/00-init-kk.sql"
if (-not (Test-Path $miSubtaskDemoInit)) { Write-Fail "Missing: 17-Multi-Instance-Subtask-Demo/00-init-kk.sql"; exit 1 }
if (-not (Exec-Sql -File $miSubtaskDemoInit -Desc "00-init-kk.sql")) { exit 1 }
$miRequestIdPatch = Join-Path $ScriptDir "17-Multi-Instance-Subtask-Demo/03-set-main-table-request-id-config.sql"
if (Test-Path $miRequestIdPatch) {
    Exec-Sql -File $miRequestIdPatch -Desc "03-set-main-table-request-id-config.sql" | Out-Null
}

Write-Step "Step 5e/6: Loading MCY Debit Card..."
$mcyInit = Join-Path $ScriptDir "18-MCY/init.sql"
if (-not (Test-Path $mcyInit)) { Write-Fail "Missing: 18-MCY/init.sql"; exit 1 }
if (-not (Exec-Sql -File $mcyInit -Desc "init.sql")) { exit 1 }

Write-Step "Step 5g/6: Loading ATM (HASE MCY Debit Card Dispute Workflow)..."
$atmInit = Join-Path $ScriptDir "19-ATM/init.sql"
if (-not (Test-Path $atmInit)) { Write-Fail "Missing: 19-ATM/init.sql"; exit 1 }
if (-not (Exec-Sql -File $atmInit -Desc "init.sql")) { exit 1 }

Write-Step "Step 5f/6: Running post-seed alignment scripts (90-post-seed/)..."
$postSeedScripts = @(
    "90-post-seed/00-align-id-sequences.sql"
)
foreach ($f in $postSeedScripts) {
    $path = Join-Path $ScriptDir $f
    if (Test-Path $path) {
        if (-not (Exec-Sql -File $path -Desc (Split-Path $f -Leaf))) { exit 1 }
    }
}

Write-Step "Step 6/6: Finished."

Write-Host ""
Write-Host "=========================================" -ForegroundColor Green
Write-Host "  Database Initialization Complete!" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
Write-Host "  Login: admin / admin123  (test: 44027893 / admin123)" -ForegroundColor White
Write-Host "  Change password after first login!" -ForegroundColor Yellow
Write-Host "  Demo function units: Platform Showcase fu-20260403-a1b2c4; Digital Lending V2 (EN) fu-20260403-a1b2c6; Meeting Participant fu-20260403-a1b2c5; Multi-Instance Subtask Demo fu-20260422-23tfag; MCY Debit Card fu-20260505-thwmut; ATM atm-20260623-gaevus" -ForegroundColor White
Write-Host "  Organization: ASP -> HK -> HASE -> hase-hmdc (01-admin/06-hase-organization-seed.sql)" -ForegroundColor White
Write-Host "  E2E users (password=password): e2e_zhangwei e2e_lina e2e_wangfang e2e_zhaomin e2e_sunqiang e2e_zhoujie e2e_wugang" -ForegroundColor White
Write-Host "=========================================" -ForegroundColor Green
