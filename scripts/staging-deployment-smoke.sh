#!/usr/bin/env bash
set -Eeuo pipefail
fail() { echo "STAGING SMOKE CHECK FAILED: $*" >&2; exit 1; }
: "${API_BASE_URL:?API_BASE_URL is required}"
: "${APP_URL:?APP_URL is required}"
: "${ACTUATOR_URL:?ACTUATOR_URL is required}"
API_BASE_URL="${API_BASE_URL%/}"; APP_URL="${APP_URL%/}"
for url in "$API_BASE_URL" "$APP_URL" "$ACTUATOR_URL"; do
  [[ "$url" == https://* ]] || fail "All staging public URLs must use HTTPS"
done
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
# Redirects are common on hosted endpoints, but must never downgrade a smoke
# request from HTTPS to HTTP or switch to another curl-supported protocol.
CURL_TRANSPORT_GUARDS=(--proto '=https' --proto-redir '=https' --max-redirs 5)
check_json() {
  local url="$1" output="$2" label="$3" status
  status="$(curl --silent --show-error --location --connect-timeout 5 --max-time 25 "${CURL_TRANSPORT_GUARDS[@]}" -o "$output" -w '%{http_code}' "$url")" || fail "$label could not be reached securely"
  [[ "$status" == "200" ]] || { echo "$label returned HTTP $status" >&2; cat "$output" >&2 || true; fail "$label HTTP status"; }
}
check_json "$ACTUATOR_URL" "$tmp/readiness.json" "Staging API readiness"
jq -e '.status == "UP"' "$tmp/readiness.json" >/dev/null || fail "Staging API readiness is not UP"
check_json "$API_BASE_URL/api/public/broadcast-health" "$tmp/broadcast-health.json" "Broadcast health"
jq -e '.status == "UP"' "$tmp/broadcast-health.json" >/dev/null || fail "Broadcast health is not UP"
frontend_status="$(curl --silent --show-error --location --connect-timeout 5 --max-time 25 "${CURL_TRANSPORT_GUARDS[@]}" -o "$tmp/frontend.html" -w '%{http_code}' "$APP_URL/")" || fail "Staging frontend could not be reached securely"
[[ "$frontend_status" == "200" ]] || fail "Staging frontend returned HTTP $frontend_status"
grep -Eqi '<!doctype html|<html([[:space:]>])' "$tmp/frontend.html" || fail "Staging frontend did not return HTML"
if [[ -n "${MATCH_ID:-}" ]]; then
  [[ "$MATCH_ID" =~ ^[a-fA-F0-9-]{36}$ ]] || fail "MATCH_ID must be a UUID"
  check_json "$API_BASE_URL/api/public/matches/$MATCH_ID/broadcast-state" "$tmp/broadcast-state.json" "Broadcast snapshot"
  jq -e --arg match "$MATCH_ID" '.match.id == $match and (.innings | type == "array")' "$tmp/broadcast-state.json" >/dev/null || fail "Broadcast snapshot does not match requested match"
else
  echo "SKIP: no MATCH_ID supplied; match-specific snapshot was not checked"
fi
echo "PASS: staging API, database-backed broadcast health, and frontend smoke checks succeeded"
