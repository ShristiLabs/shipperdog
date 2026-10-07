#!/usr/bin/env bash
# Portal security-header check (issue #39, S8-05): builds the portal
# image exactly as deployed (nginx + hardened conf), stands it up with
# the api upstream stub-resolved (no stack needed), and asserts the
# securityheaders.com-grade header set on the SPA entry, an unknown
# client-side route (the SPA fallback), and a content-hashed asset.
# Also asserts the shipped bundle carries no inline <script>, which
# the strict CSP (`script-src 'self'`) would refuse to execute.
#
# Usage: infrastructure/security-headers/check-portal-headers.sh
# Requires: docker, curl, python3 (header parsing).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORTAL_DIR="${REPO_ROOT}/portal"
IMAGE="shipperdog-portal-headers-check:$$"
CONTAINER="portal-headers-check-$$"
PORT="${PORTAL_HEADERS_CHECK_PORT:-18923}"

cleanup() {
  docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  docker rmi "${IMAGE}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== building portal image"
docker build -q -t "${IMAGE}" "${PORTAL_DIR}" >/dev/null

echo "== asserting no inline <script> in the shipped bundle"
INLINE_SCRIPTS="$(docker run --rm --entrypoint sh "${IMAGE}" -c \
  "grep -c '<script>[^<]' /usr/share/nginx/html/index.html || true")"
if [ "${INLINE_SCRIPTS}" != "0" ]; then
  echo "FAIL: index.html contains an inline script; CSP script-src 'self' would not execute it" >&2
  exit 1
fi

echo "== starting hardened portal"
# --add-host + API_UPSTREAM: nginx resolves the api upstream at
# startup; the header check never proxies, a loopback stub keeps the
# template's upstream resolvable.
docker run -d --rm --name "${CONTAINER}" -e API_UPSTREAM=api:8080 \
  --add-host api:127.0.0.1 -p "${PORT}:80" "${IMAGE}" >/dev/null

for _ in $(seq 1 30); do
  if curl -fsS -o /dev/null "http://localhost:${PORT}/"; then break; fi
  sleep 1
done

ASSET="$(curl -fsS "http://localhost:${PORT}/" | grep -o '/assets/[^"]*\.js' | head -1 || true)"
if [ -z "${ASSET}" ]; then
  echo "FAIL: no hashed js asset referenced from the SPA entry" >&2
  exit 1
fi

check_path() {
  local path="$1"
  python3 "${REPO_ROOT}/infrastructure/security-headers/assert_headers.py" \
    <(curl -fsS -D - -o /dev/null "http://localhost:${PORT}${path}") "${path}"
}

check_path "/"
check_path "/accept-invitation" # SPA fallback: the 200 index route must carry the set too
check_path "${ASSET}"

echo "PASS: security-header set present on /, SPA fallback, and hashed asset"
