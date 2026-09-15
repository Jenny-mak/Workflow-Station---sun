#!/bin/bash
# Initialize Superset on the host (requires Docker CLI):
#   1. break-glass DB admin `admin` (authors normally arrive via platform SSO / REMOTE_USER JIT),
#   2. the BI guest-token service account admin-center logs in with (BI_SUPERSET_USERNAME/PASSWORD,
#      provider=db) — neither `superset init` nor SSO JIT creates it, so a rebuilt metadata DB has
#      none until this runs and every portal embed fails with Superset 401,
#   3. `superset init` (roles/permissions; also runs the custom SM's Gamma self-heal hook).
# Do NOT place this under deploy/init-scripts/ — postgres auto-runs *.sh there.
# Usage: ./deploy/scripts/superset-init.sh [container-name] [env-file]
#   env-file defaults to deploy/environments/dev/.env; BI_SUPERSET_USERNAME / BI_SUPERSET_PASSWORD
#   already exported in the shell take precedence over the file.
set -e
CONTAINER="${1:-platform-superset-dev}"
ENV_FILE="${2:-$(cd "$(dirname "$0")/.." && pwd)/environments/dev/.env}"

docker exec "$CONTAINER" superset fab create-admin \
    --username admin --firstname Admin --lastname User \
    --email admin@superset.com --password admin123 \
    || echo "Superset admin may already exist (skipping create-admin)."

BI_USER="${BI_SUPERSET_USERNAME:-}"
BI_PASS="${BI_SUPERSET_PASSWORD:-}"
if [ -f "$ENV_FILE" ]; then
    [ -n "$BI_USER" ] || BI_USER="$(grep -E '^BI_SUPERSET_USERNAME=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
    [ -n "$BI_PASS" ] || BI_PASS="$(grep -E '^BI_SUPERSET_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
fi
if [ -n "$BI_USER" ] && [ -n "$BI_PASS" ]; then
    docker exec "$CONTAINER" superset fab create-admin \
        --username "$BI_USER" --firstname BI --lastname ServiceAccount \
        --email "${BI_USER}@bi.workflow.local" --password "$BI_PASS" \
        || echo "Superset BI service account '$BI_USER' may already exist (skipping create-admin)."
else
    echo "WARN: BI_SUPERSET_USERNAME/BI_SUPERSET_PASSWORD not set and not found in $ENV_FILE;" \
         "guest-token service account NOT created — admin-center /bi/guest-token will get 401 from Superset."
fi

docker exec "$CONTAINER" superset init
echo "Superset init completed."
