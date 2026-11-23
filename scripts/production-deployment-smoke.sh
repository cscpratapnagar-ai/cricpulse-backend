#!/usr/bin/env bash
set -euo pipefail

fail() { echo "DEPLOYMENT SMOKE CHECK FAILED: $*" >&2; exit 1; }

: "${API_BASE_URL:?API_BASE_URL must be the externally reachable API origin, e.g. https://cricpulse.example.com}"
API_BASE_URL="${API_BASE_URL%/}"
APP_URL="${APP_URL:-}"
REQUIRE_HTTPS="${REQUIRE_HTTPS:-true}"
MATCH_ID="${MATCH_ID:-}"

if [[ "$REQUIRE_HTTPS" == "true" ]]; then
  [[ "$API_BASE_URL" == https://* ]] || fail "API_BASE_URL must use HTTPS in production"
  if [[ -n "$APP_URL" ]]; then
    [[ "$APP_URL" == https://* ]] || fail "APP_URL must use HTTPS in production"
  fi
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

check_json() {
  local url="$1" output="$2" description="$3" expected_status="$4"
  local status
  status="$(curl --silent --show-error --location --connect-timeout 5 --max-time 20 -o "$output" -w '%{http_code}' "$url")" || fail "$description could not be reached"
  [[ "$status" == "$expected_status" ]] || {
    echo "$description returned HTTP $status (expected $expected_status)" >&2
    cat "$output" >&2 || true
    fail "$description HTTP status check"
  }
}

echo "[1/4] Verify Spring Boot readiness"
check_json "$API_BASE_URL/actuator/health/readiness" "$tmp/readiness.json" "API readiness" "200"
jq -e '.status == "UP"' "$tmp/readiness.json" >/dev/null || fail "API readiness is not UP"

echo "[2/4] Verify database-backed public broadcast readiness"
check_json "$API_BASE_URL/api/public/broadcast-health" "$tmp/broadcast-health.json" "Broadcast readiness" "200"
jq -e '.status == "UP"' "$tmp/broadcast-health.json" >/dev/null || fail "Broadcast readiness is not UP"

echo "[3/4] Verify the deployed frontend (when APP_URL is supplied)"
if [[ -n "$APP_URL" ]]; then
  APP_URL="${APP_URL%/}"
  frontend_status="$(curl --silent --show-error --location --connect-timeout 5 --max-time 20 -o "$tmp/frontend.html" -w '%{http_code}' "$APP_URL/")" || fail "Frontend could not be reached"
  [[ "$frontend_status" == "200" ]] || fail "Frontend returned HTTP $frontend_status"
  grep -Eqi '<!doctype html|<html([[:space:]>])' "$tmp/frontend.html" || fail "Frontend root did not return an HTML document"
else
  echo "SKIP: APP_URL not supplied; frontend host check was not run"
fi

echo "[4/4] Verify the live broadcast snapshot (when MATCH_ID is supplied)"
if [[ -n "$MATCH_ID" ]]; then
  check_json "$API_BASE_URL/api/public/matches/$MATCH_ID/broadcast-state" "$tmp/broadcast-state.json" "Broadcast state" "200"
  jq -e --arg match "$MATCH_ID" '.match.id == $match and (.innings | type == "array")' "$tmp/broadcast-state.json" >/dev/null || fail "Broadcast snapshot does not match MATCH_ID or has no innings array"
else
  echo "SKIP: MATCH_ID not supplied; match-specific snapshot check was not run"
fi

echo "PASS: deployed API readiness and database-backed broadcast readiness are UP"
