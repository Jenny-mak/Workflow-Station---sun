#!/bin/bash
# =====================================================
# Database Initialization (Docker entrypoint)
# =====================================================
# Auto-executed by PostgreSQL on first container start.
# Creates: schemas → roles/groups → admin user → single demo function unit
# =====================================================

set -e

PSQL="psql -v ON_ERROR_STOP=1 --username $POSTGRES_USER --dbname $POSTGRES_DB"

echo "========================================="
echo "  Database Initialization Starting..."
echo "========================================="

# --- Step 0: Flowable engine schema ---
# Runs first so that 00-schema/30- and 31- (Step 2) find act_* already there. Those two widen
# act_ru/hi_identitylink and act_hi_comment with ALTER TABLE IF EXISTS; before this step existed
# the tables were only created later, by the engine itself at startup, so the ALTERs silently
# no-op'd on a fresh database and the widening never applied. Long virtual group ids then blew up
# task completion with "value too long for type character varying(255)".
#
# Composition is all vendor SQL, nothing hand-assembled: Flowable ships the all-in-one create at
# 7.0.0 plus separate incremental upgrade steps. create + the three 7.x steps lands on 7.2.0.2,
# which is what the engine expects — verified against an engine-built schema, the core act_*/flw_*
# tables match column for column.
#
# When flowable.version changes in pom.xml: drop the new vendor upgradestep file into
# /flowable-sql/upgrade/ and append it here. The engine runs with database-schema-update=false, so
# a mismatch fails startup loudly instead of silently migrating the database.
FLOWABLE_UPGRADE_STEPS="
7.0.0.to.7.0.1
7.0.1.to.7.1.0
7.1.0.to.7.2.0
"

echo ""
echo "[0/7] Creating Flowable engine schema..."
if [ -f /flowable-sql/create/flowable.postgres.all.create.sql ]; then
    echo "  Running flowable.postgres.all.create.sql (7.0.0 baseline)..."
    $PSQL -f /flowable-sql/create/flowable.postgres.all.create.sql
    for step in $FLOWABLE_UPGRADE_STEPS; do
        f="/flowable-sql/upgrade/flowable.postgres.upgradestep.${step}.all.sql"
        [ -f "$f" ] && echo "  Running upgradestep ${step}..." && $PSQL -f "$f"
    done
    echo "  Flowable schema at: $($PSQL -t -A -c "SELECT value_ FROM act_ge_property WHERE name_='schema.version'")"
else
    # Not fatal here, but the engine runs with database-schema-update=false and will refuse to
    # start against a database with no act_* tables, saying exactly that.
    echo "  WARNING: /flowable-sql not mounted; Flowable tables NOT created."
fi

# --- Step 1: Base schemas ---
echo ""
echo "[1/7] Creating base schemas..."
for f in /docker-entrypoint-initdb.d/00-schema/01-*.sql \
         /docker-entrypoint-initdb.d/00-schema/02-*.sql \
         /docker-entrypoint-initdb.d/00-schema/03-*.sql \
         /docker-entrypoint-initdb.d/00-schema/04-*.sql \
         /docker-entrypoint-initdb.d/00-schema/05-*.sql; do
    [ -f "$f" ] && echo "  Running $(basename $f)..." && $PSQL -f "$f"
done

# --- Step 2: Incremental migrations ---
# Note: 09-* reserved (intentionally unused). Flowable act_*/flw_* tables are created in Step 0
# above, before this step, so that 30-/31- find them and the widening actually applies.
# Schema management is manual in every environment (flowable.database-schema-update=false):
#   version upgrade  -> deploy/k8s/init-data/init-flowable/upgrade/MIGRATE-*.sql
#   corrupted schema -> 99-maintenance/01-repair-flowable-schema.sql (drops; then recreate the
#                       database so Step 0 runs again, or replay create + the upgrade steps)
echo ""
echo "[2/7] Applying incremental migrations..."
for f in /docker-entrypoint-initdb.d/00-schema/06-*.sql \
         /docker-entrypoint-initdb.d/00-schema/07-*.sql \
         /docker-entrypoint-initdb.d/00-schema/08-*.sql \
         /docker-entrypoint-initdb.d/00-schema/10-*.sql \
         /docker-entrypoint-initdb.d/00-schema/11-*.sql \
         /docker-entrypoint-initdb.d/00-schema/12-*.sql \
         /docker-entrypoint-initdb.d/00-schema/13-*.sql \
         /docker-entrypoint-initdb.d/00-schema/15-*.sql \
         /docker-entrypoint-initdb.d/00-schema/16-*.sql \
         /docker-entrypoint-initdb.d/00-schema/17-*.sql \
         /docker-entrypoint-initdb.d/00-schema/18-*.sql \
         /docker-entrypoint-initdb.d/00-schema/19-*.sql \
         /docker-entrypoint-initdb.d/00-schema/20-*.sql \
         /docker-entrypoint-initdb.d/00-schema/21-*.sql \
         /docker-entrypoint-initdb.d/00-schema/22-*.sql \
         /docker-entrypoint-initdb.d/00-schema/23-*.sql \
         /docker-entrypoint-initdb.d/00-schema/24-*.sql \
         /docker-entrypoint-initdb.d/00-schema/25-*.sql \
         /docker-entrypoint-initdb.d/00-schema/26-*.sql \
         /docker-entrypoint-initdb.d/00-schema/27-*.sql \
         /docker-entrypoint-initdb.d/00-schema/28-*.sql \
         /docker-entrypoint-initdb.d/00-schema/29-*.sql \
         /docker-entrypoint-initdb.d/00-schema/30-*.sql \
         /docker-entrypoint-initdb.d/00-schema/31-*.sql \
         /docker-entrypoint-initdb.d/00-schema/32-*.sql \
         /docker-entrypoint-initdb.d/00-schema/33-*.sql \
         /docker-entrypoint-initdb.d/00-schema/34-*.sql \
         /docker-entrypoint-initdb.d/00-schema/35-*.sql \
         /docker-entrypoint-initdb.d/00-schema/36-*.sql \
         /docker-entrypoint-initdb.d/00-schema/37-*.sql \
         /docker-entrypoint-initdb.d/00-schema/38-*.sql \
         /docker-entrypoint-initdb.d/00-schema/39-*.sql \
         /docker-entrypoint-initdb.d/00-schema/40-*.sql \
         /docker-entrypoint-initdb.d/00-schema/41-*.sql \
         /docker-entrypoint-initdb.d/00-schema/42-*.sql \
         /docker-entrypoint-initdb.d/00-schema/43-*.sql \
         /docker-entrypoint-initdb.d/00-schema/44-*.sql \
         /docker-entrypoint-initdb.d/00-schema/45-*.sql \
         /docker-entrypoint-initdb.d/00-schema/46-*.sql \
         /docker-entrypoint-initdb.d/00-schema/47-*.sql \
         /docker-entrypoint-initdb.d/00-schema/48-*.sql \
         /docker-entrypoint-initdb.d/00-schema/49-*.sql \
         /docker-entrypoint-initdb.d/00-schema/50-*.sql \
         /docker-entrypoint-initdb.d/00-schema/51-*.sql \
         /docker-entrypoint-initdb.d/00-schema/52-*.sql \
         /docker-entrypoint-initdb.d/00-schema/53-*.sql \
         /docker-entrypoint-initdb.d/00-schema/54-*.sql \
         /docker-entrypoint-initdb.d/00-schema/55-*.sql \
         /docker-entrypoint-initdb.d/00-schema/56-*.sql \
         /docker-entrypoint-initdb.d/00-schema/57-*.sql \
         /docker-entrypoint-initdb.d/00-schema/58-*.sql \
         /docker-entrypoint-initdb.d/00-schema/59-*.sql \
         /docker-entrypoint-initdb.d/00-schema/60-*.sql \
         /docker-entrypoint-initdb.d/00-schema/61-*.sql \
         /docker-entrypoint-initdb.d/00-schema/62-*.sql \
         /docker-entrypoint-initdb.d/00-schema/63-*.sql \
         /docker-entrypoint-initdb.d/00-schema/64-*.sql \
         /docker-entrypoint-initdb.d/00-schema/65-*.sql; do
    [ -f "$f" ] && echo "  Running $(basename $f)..." && $PSQL -f "$f"
done

# --- Step 3: Roles, groups, admin user ---
echo ""
echo "[3/7] Creating roles, groups, and admin user..."
$PSQL -f /docker-entrypoint-initdb.d/01-admin/01-create-roles-and-groups.sql
$PSQL -f /docker-entrypoint-initdb.d/01-admin/01-create-admin-only.sql
$PSQL -f /docker-entrypoint-initdb.d/01-admin/02-init-developer-permissions.sql
$PSQL -f /docker-entrypoint-initdb.d/01-admin/03-sync-role-tables.sql
$PSQL -f /docker-entrypoint-initdb.d/01-admin/04-admin-permissions.sql
$PSQL -f /docker-entrypoint-initdb.d/01-admin/05-e2e-test-users-and-business-units.sql
$PSQL -f /docker-entrypoint-initdb.d/01-admin/06-hase-organization-seed.sql
# Built-in Public dev group (always-visible overlay for the function-unit workspace).
# On a fresh DB the FU-migration steps are no-ops (no function units yet); it just creates the group.
$PSQL -f /docker-entrypoint-initdb.d/01-admin/08-fu-public-group-migration.sql
# Remove deprecated FU_VIEWER role seeded by earlier 01-admin scripts (append-only cleanup).
$PSQL -f /docker-entrypoint-initdb.d/01-admin/09-remove-fu-viewer-role.sql

# Step 4 used to run 99-maintenance/00-wipe-all-function-units.sql here. Removed: this script
# only runs when the data directory is empty, and Steps 1-3 create no function units, so the
# wipe had nothing to clear. What it did do was DROP every act_*/flw_* table, which forced the
# Flowable schema to be built after it instead of in its natural place (Step 0). The wipe script
# stays in 99-maintenance/ for its real purpose: manually re-seeding an existing database.

echo ""
echo "[4a/7] Loading Platform Showcase (company promo demo)..."
for f in /docker-entrypoint-initdb.d/15-platform-showcase/00-*.sql \
         /docker-entrypoint-initdb.d/15-platform-showcase/01-*.sql \
         /docker-entrypoint-initdb.d/15-platform-showcase/02-*.sql \
         /docker-entrypoint-initdb.d/15-platform-showcase/03-*.sql \
         /docker-entrypoint-initdb.d/15-platform-showcase/04-*.sql \
         /docker-entrypoint-initdb.d/15-platform-showcase/05-*.sql; do
  [ -f "$f" ] && echo "  Running $(basename $f)..." && $PSQL -f "$f"
done

echo ""
echo "[4b/7] Loading Digital Lending V2 EN..."
for f in /docker-entrypoint-initdb.d/08-digital-lending-v2-en/00-*.sql \
         /docker-entrypoint-initdb.d/08-digital-lending-v2-en/01-*.sql \
         /docker-entrypoint-initdb.d/08-digital-lending-v2-en/02-*.sql \
         /docker-entrypoint-initdb.d/08-digital-lending-v2-en/03-*.sql \
         /docker-entrypoint-initdb.d/08-digital-lending-v2-en/04-*.sql; do
    [ -f "$f" ] && echo "  Running $(basename $f)..." && $PSQL -f "$f"
done

if [ -f /docker-entrypoint-initdb.d/08-digital-lending-v2-en/05-e2e-virtual-group-members.sql ]; then
  echo ""
  echo "  Running 05-e2e-virtual-group-members.sql..."
  $PSQL -f /docker-entrypoint-initdb.d/08-digital-lending-v2-en/05-e2e-virtual-group-members.sql
fi

echo ""
echo "[4c/7] Loading Meeting Participant Info Collection..."
for f in /docker-entrypoint-initdb.d/16-meeting-participant-collection/00-*.sql \
         /docker-entrypoint-initdb.d/16-meeting-participant-collection/01-*.sql \
         /docker-entrypoint-initdb.d/16-meeting-participant-collection/02-*.sql \
         /docker-entrypoint-initdb.d/16-meeting-participant-collection/03-*.sql \
         /docker-entrypoint-initdb.d/16-meeting-participant-collection/04-*.sql \
         /docker-entrypoint-initdb.d/16-meeting-participant-collection/05-*.sql \
         /docker-entrypoint-initdb.d/16-meeting-participant-collection/06-*.sql; do
  [ -f "$f" ] && echo "  Running $(basename $f)..." && $PSQL -f "$f"
done

echo ""
echo "[4d/7] Loading Function Unit Multi-Instance Subtask Demo..."
if [ -f /docker-entrypoint-initdb.d/17-Multi-Instance-Subtask-Demo/00-init-kk.sql ]; then
  echo "  Running 00-init-kk.sql..."
  $PSQL -f /docker-entrypoint-initdb.d/17-Multi-Instance-Subtask-Demo/00-init-kk.sql
  if [ -f /docker-entrypoint-initdb.d/17-Multi-Instance-Subtask-Demo/03-set-main-table-request-id-config.sql ]; then
    echo "  Running 03-set-main-table-request-id-config.sql..."
    $PSQL -f /docker-entrypoint-initdb.d/17-Multi-Instance-Subtask-Demo/03-set-main-table-request-id-config.sql
  fi
else
  echo "  ERROR: Multi-Instance Subtask Demo init script not found at /docker-entrypoint-initdb.d/17-Multi-Instance-Subtask-Demo/00-init-kk.sql"
  exit 1
fi

echo ""
echo "[4e/7] Loading MCY Debit Card..."
if [ -f /docker-entrypoint-initdb.d/18-MCY/init.sql ]; then
  echo "  Running init.sql..."
  $PSQL -f /docker-entrypoint-initdb.d/18-MCY/init.sql
else
  echo "  ERROR: MCY Debit Card init script not found at /docker-entrypoint-initdb.d/18-MCY/init.sql"
  exit 1
fi

echo ""
echo "[4f/7] Loading ATM (HASE MCY Debit Card Dispute Workflow)..."
if [ -f /docker-entrypoint-initdb.d/19-ATM/init.sql ]; then
  echo "  Running init.sql..."
  $PSQL -f /docker-entrypoint-initdb.d/19-ATM/init.sql
else
  echo "  ERROR: ATM init script not found at /docker-entrypoint-initdb.d/19-ATM/init.sql"
  exit 1
fi

# --- Step 5f: Post-seed alignment ---
# Scripts under 90-post-seed/ run on every init, AFTER all seed packages above.
# They are not DDL and not seed data -- they reconcile state introduced by the
# seed step (e.g. pushing BIGSERIAL sequences past explicit-id seed rows so the
# next JPA `GenerationType.IDENTITY` insert does not collide on the primary key).
echo ""
echo "[5/7] Running post-seed alignment scripts (90-post-seed/)..."
for f in /docker-entrypoint-initdb.d/90-post-seed/00-*.sql \
         /docker-entrypoint-initdb.d/90-post-seed/01-*.sql \
         /docker-entrypoint-initdb.d/90-post-seed/02-*.sql \
         /docker-entrypoint-initdb.d/90-post-seed/03-*.sql \
         /docker-entrypoint-initdb.d/90-post-seed/04-*.sql \
         /docker-entrypoint-initdb.d/90-post-seed/05-*.sql; do
  [ -f "$f" ] && echo "  Running $(basename $f)..." && $PSQL -f "$f"
done

echo ""
echo "[6/7] Seed scripts finished."

echo ""
echo "========================================="
echo "  Database Initialization Complete!"
echo "========================================="
echo "  Login: admin / admin123  (test: 44027893 / admin123)"
echo "  Change password after first login!"
echo "  Demo function units: Platform Showcase fu-20260403-a1b2c4; Digital Lending System V2 (EN) fu-20260403-a1b2c6; Meeting Participant Info Collection fu-20260403-a1b2c5; Multi-Instance Subtask Demo fu-20260422-23tfag; MCY Debit Card fu-20260505-thwmut; ATM atm-20260623-gaevus"
echo "  Organization: ASP → HK → HASE → hase-hmdc (see 01-admin/06-hase-organization-seed.sql)"
echo "  E2E users (password=password): e2e_zhangwei e2e_lina e2e_wangfang e2e_zhaomin e2e_sunqiang e2e_zhoujie e2e_wugang"
echo "  (Re-seed: run init-scripts/99-maintenance/00-wipe-all-function-units.sql then reload 08-, 16-, 17-, 18-, and 19- scripts if needed.)"
echo "========================================="
