#!/usr/bin/env bash
# Post-deploy smoke test (issue #39, S8-05): the three-leg proof that
# a deployment is actually alive and correct end to end —
#
#   1. health  — the api's /actuator/health answers UP;
#   2. login   — the seeded demo tester authenticates through the
#                portal's same-origin proxy (identity tier + wiring);
#   3. catalog — the tester's authorized application catalog is
#                non-empty (the authorization engine, the seeded demo
#                org, and the grant chain all work).
#
# Plus the portal origin carries the security-header set (the same
# contract the CI header check pins).
#
# Usage:
#   API_BASE_URL=https://api... PORTAL_BASE_URL=https://portal... \
#   SMOKE_EMAIL=tester@shipperdog.local SMOKE_PASSWORD=... \
#   infrastructure/smoke/smoke.sh
#
# Against the local compose stack (defaults): stand the stack up first
# (docker compose up -d --build), then run this script with no args.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
PORTAL_BASE_URL="${PORTAL_BASE_URL:-http://localhost:5174}"
SMOKE_EMAIL="${SMOKE_EMAIL:-tester@shipperdog.local}"
SMOKE_PASSWORD="${SMOKE_PASSWORD:-shipperdog-dev-tester}"

fail() {
  echo "SMOKE FAIL: $*" >&2
  exit 1
}

echo "== [1/4] health: ${API_BASE_URL}/actuator/health"
HEALTH="$(curl -fsS --max-time 15 "${API_BASE_URL}/actuator/health")" || fail "health endpoint unreachable"
echo "${HEALTH}" | grep -q '"UP"' || fail "health is not UP: ${HEALTH}"
echo "   UP"

echo "== [2/4] portal security headers: ${PORTAL_BASE_URL}/"
curl -fsS -D /tmp/smoke-portal-headers.$$ -o /dev/null "${PORTAL_BASE_URL}/" \
  || fail "portal unreachable"
python3 "${REPO_ROOT}/infrastructure/security-headers/assert_headers.py" \
  /tmp/smoke-portal-headers.$$ "${PORTAL_BASE_URL}" || fail "portal security-header set incomplete"
rm -f /tmp/smoke-portal-headers.$$

echo "== [3/4] login: ${SMOKE_EMAIL} via ${PORTAL_BASE_URL}/api/v1/auth/login"
LOGIN_BODY="$(python3 - "$SMOKE_EMAIL" "$SMOKE_PASSWORD" <<'PY'
import json, sys
print(json.dumps({"email": sys.argv[1], "password": sys.argv[2]}))
PY
)"
LOGIN_RESPONSE="$(curl -fsS --max-time 15 -X POST \
  -H "Content-Type: application/json" \
  -d "${LOGIN_BODY}" \
  "${PORTAL_BASE_URL}/api/v1/auth/login")" || fail "login rejected (credentials: SMOKE_EMAIL/SMOKE_PASSWORD)"
ACCESS_TOKEN="$(echo "${LOGIN_RESPONSE}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')" \
  || fail "login response carries no accessToken"
echo "   authenticated"

echo "== [4/4] catalog: ${PORTAL_BASE_URL}/api/v1/authorization/decisions/applications"
CATALOG="$(curl -fsS --max-time 15 \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  "${PORTAL_BASE_URL}/api/v1/authorization/decisions/applications?limit=10")" || fail "catalog request failed"
echo "${CATALOG}" | python3 -c '
import json, sys
catalog = json.load(sys.stdin)
items = catalog.get("items", [])
if not items:
    sys.exit("catalog is empty — the grant chain is not serving the demo org")
names = ", ".join(item.get("application", {}).get("name", "?") for item in items)
print(f"   {len(items)} application(s): {names}")
' || fail "catalog verification failed"

echo "SMOKE PASS: health UP, headers present, login OK, catalog non-empty"
