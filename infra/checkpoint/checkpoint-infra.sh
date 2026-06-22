#!/usr/bin/env bash
# Infrastructure checkpoint (tasks.md task 3).
# Verifies the one-command stack: docker-compose up brings the platform online,
# Keycloak issues a JWT, and Kong routes an authenticated request to a health stub
# while rejecting unauthenticated (401) and unknown (404) requests.
#
# Usage:  infra/checkpoint/checkpoint-infra.sh            # bring up, verify, leave running
#         KEEP_UP=0 infra/checkpoint/checkpoint-infra.sh  # tear the stack down afterwards
set -euo pipefail

cd "$(dirname "$0")/../.."

COMPOSE=(docker compose --profile checkpoint)

KONG_PROXY="http://localhost:${KONG_PROXY_PORT:-8000}"
KEYCLOAK="http://localhost:${KEYCLOAK_PORT:-8080}"
REALM="${KEYCLOAK_REALM:-dynamic-pricing}"
DEMO_USER="${DEMO_USER:-demo.customer}"
DEMO_PASS="${DEMO_PASS:-demo_customer_dev_only}"
KEEP_UP="${KEEP_UP:-1}"
# Keycloak imports the declarative realm only when its data volume is empty
# (strategy IGNORE_EXISTING). Reset it by default so the realm — including the
# seeded demo users — is always (re)imported and the checkpoint is reproducible.
FRESH_KEYCLOAK="${FRESH_KEYCLOAK:-1}"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
info() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

cleanup() {
  if [[ "$KEEP_UP" != "1" ]]; then
    info "Tearing down stack (KEEP_UP=$KEEP_UP)"
    "${COMPOSE[@]}" down
  fi
}
trap cleanup EXIT

if [[ "$FRESH_KEYCLOAK" == "1" ]]; then
  info "Resetting Keycloak data volume to force realm import"
  "${COMPOSE[@]}" rm -fsv keycloak >/dev/null 2>&1 || true
  docker volume rm "${COMPOSE_PROJECT_NAME:-dynamic-pricing-platform}_keycloak_data" >/dev/null 2>&1 || true
fi

info "Starting full stack with checkpoint profile (docker-compose up)"
"${COMPOSE[@]}" up -d

# ---------------------------------------------------------------------------
# Wait until the gateway-critical containers report healthy (<= ~5 min budget).
# ---------------------------------------------------------------------------
wait_healthy() {
  local name="$1" deadline=$((SECONDS + 300))
  while (( SECONDS < deadline )); do
    local status
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null || echo missing)"
    case "$status" in
      healthy) pass "$name is healthy"; return 0 ;;
      missing) fail "$name container not found" ;;
    esac
    sleep 5
  done
  fail "$name did not become healthy within 300s"
}

info "Waiting for infrastructure health"
wait_healthy dpp-keycloak
wait_healthy dpp-kong
wait_healthy dpp-health-stub

# ---------------------------------------------------------------------------
# 1. Keycloak issues a JWT via the mini-app direct access grant.
# ---------------------------------------------------------------------------
info "Verifying Keycloak token issuance"
TOKEN_RESPONSE="$(curl -s -X POST \
  "$KEYCLOAK/realms/$REALM/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=mini-app' \
  -d "username=$DEMO_USER" \
  -d "password=$DEMO_PASS")"

ACCESS_TOKEN="$(printf '%s' "$TOKEN_RESPONSE" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
[[ -n "$ACCESS_TOKEN" ]] || fail "Keycloak did not return an access_token: $TOKEN_RESPONSE"
pass "Keycloak issued a JWT for $DEMO_USER"

# ---------------------------------------------------------------------------
# 2. Kong rejects an unauthenticated request (R9.3/R18.2 -> 401).
# ---------------------------------------------------------------------------
info "Verifying Kong gateway routing"
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$KONG_PROXY/customers/anything")"
[[ "$CODE" == "401" ]] && pass "No JWT -> 401" || fail "No JWT expected 401, got $CODE"

# 3. Kong routes an authenticated request to the health stub (R9.1 -> 200).
CODE="$(curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $ACCESS_TOKEN" "$KONG_PROXY/customers/anything")"
[[ "$CODE" == "200" ]] && pass "Valid JWT -> 200 via stub" || fail "Valid JWT expected 200, got $CODE"

# 4. Unknown route -> 404 (R9.2).
CODE="$(curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $ACCESS_TOKEN" "$KONG_PROXY/no-such-route")"
[[ "$CODE" == "404" ]] && pass "Unknown route -> 404" || fail "Unknown route expected 404, got $CODE"

info "Infrastructure checkpoint PASSED"
