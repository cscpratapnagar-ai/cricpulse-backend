#!/usr/bin/env bash
set -euo pipefail

# Tournament delegated-manager authorization E2E.
# Uses disposable users and a tournament created by ci-tournament-setup.sh.
# Proves the complete grant -> scoped access -> denied privilege escalation ->
# revoke -> access removed lifecycle over the real HTTP API.

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
OWNER_EMAIL="${E2E_EMAIL:?E2E_EMAIL is required}"
MANAGER_EMAIL="${E2E_OWNER_B_EMAIL:?E2E_OWNER_B_EMAIL is required}"
PASSWORD="${E2E_PASSWORD:?E2E_PASSWORD is required}"
TOURNAMENT_ID="${E2E_TOURNAMENT_ID:?E2E_TOURNAMENT_ID is required}"

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

login_token() {
  local email="$1" jar="$TMP_DIR/$(printf '%s' "$email" | tr '@.' '__').cookies" csrf token status
  curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" >/dev/null
  csrf="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$jar")"
  [[ -n "$csrf" ]] || { echo "ERROR: CSRF token missing for $email" >&2; return 1; }
  status="$(curl -sS -b "$jar" -c "$jar" -o "$TMP_DIR/login.json" -w '%{http_code}' \
    -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf" \
    --data "$(jq -nc --arg email "$email" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
  [[ "$status" == "200" ]] || { echo "ERROR: login for $email returned HTTP $status" >&2; cat "$TMP_DIR/login.json" >&2; return 1; }
  token="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$jar")"
  [[ -n "$token" ]] || { echo "ERROR: HttpOnly access cookie missing for $email" >&2; return 1; }
  printf '%s' "$token"
}

OWNER_TOKEN="$(login_token "$OWNER_EMAIL")"
MANAGER_TOKEN="$(login_token "$MANAGER_EMAIL")"

request_status() {
  local expected="$1" token="$2" method="$3" path="$4" body="${5:-}" status
  local output="$TMP_DIR/response.json"
  if [[ -n "$body" ]]; then
    status="$(curl -sS -o "$output" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      --data "$body")"
  else
    status="$(curl -sS -o "$output" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
      -H "Authorization: Bearer $token" -H 'Accept: application/json')"
  fi
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL: $method $path expected HTTP $expected, got $status" >&2
    cat "$output" >&2
    exit 1
  fi
  echo "PASS: $method $path -> HTTP $status"
  cat "$output"
}

assert_mine_contains() {
  local token="$1" expected="$2" response
  response="$(curl -fsS "$BASE_URL/tournaments/mine" -H "Authorization: Bearer $token")"
  jq -e --arg id "$TOURNAMENT_ID" --argjson expected "$expected" \
    '(([.[] | .id] | index($id)) != null) == $expected' <<<"$response" >/dev/null || {
      echo "FAIL: /tournaments/mine membership did not match expected=$expected" >&2
      exit 1
    }
}

echo "[1/7] Confirm the second user has no access before assignment"
request_status 404 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID" >/dev/null
request_status 404 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/staff" >/dev/null
assert_mine_contains "$MANAGER_TOKEN" false
echo "PASS: unassigned user cannot view or discover the tournament"

echo "[2/7] Owner grants tournament-scoped manager access"
request_status 200 "$OWNER_TOKEN" PUT "/tournaments/$TOURNAMENT_ID/staff/manager" \
  "$(jq -nc --argjson id "$(curl -fsS "$BASE_URL/players/me" -H "Authorization: Bearer $MANAGER_TOKEN" | jq -r '.userId // empty')" '{userId:$id}')" >/dev/null
# The manager candidate's account UUID is obtained from the owner's candidate endpoint,
# not inferred from a player profile. Re-search and grant using the returned account ID.
CANDIDATES="$(curl -fsS --get "$BASE_URL/tournaments/$TOURNAMENT_ID/staff/candidates" \
  -H "Authorization: Bearer $OWNER_TOKEN" --data-urlencode "q=$MANAGER_EMAIL")"
MANAGER_USER_ID="$(jq -r --arg email "$MANAGER_EMAIL" '.[] | select((.email // "" | ascii_downcase) == ($email | ascii_downcase)) | .userId' <<<"$CANDIDATES" | head -n 1)"
[[ -n "$MANAGER_USER_ID" && "$MANAGER_USER_ID" != "null" ]] || {
  echo "ERROR: manager account not returned by owner-only candidate search" >&2
  exit 1
}
request_status 200 "$OWNER_TOKEN" PUT "/tournaments/$TOURNAMENT_ID/staff/manager" \
  "$(jq -nc --arg id "$MANAGER_USER_ID" '{userId:$id}')" >/dev/null
# Granting the same manager twice must remain idempotent.
request_status 200 "$OWNER_TOKEN" PUT "/tournaments/$TOURNAMENT_ID/staff/manager" \
  "$(jq -nc --arg id "$MANAGER_USER_ID" '{userId:$id}')" >/dev/null

echo "[3/7] Verify delegated access is limited to tournament operations"
STAFF_RESPONSE="$(request_status 200 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/staff")"
jq -e '.canManage == false and ([.managers[] | select(.role == "MANAGER")] | length == 1)' \
  <<<"$STAFF_RESPONSE" >/dev/null || { echo "FAIL: manager view/capability contract is incorrect" >&2; exit 1; }
request_status 200 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID" >/dev/null
request_status 200 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/teams" >/dev/null
request_status 200 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/fixtures" >/dev/null
request_status 200 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/points-table" >/dev/null
request_status 200 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/analytics" >/dev/null
assert_mine_contains "$MANAGER_TOKEN" true
OWNER_STAFF="$(request_status 200 "$OWNER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/staff")"
jq -e '.canManage == true and ([.managers[] | select(.role == "MANAGER")] | length == 1)' \
  <<<"$OWNER_STAFF" >/dev/null || { echo "FAIL: owner manager administration capability missing" >&2; exit 1; }

echo "[4/7] Verify delegated manager cannot escalate privileges"
request_status 403 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/staff/candidates?q=owner" >/dev/null
request_status 403 "$MANAGER_TOKEN" PUT "/tournaments/$TOURNAMENT_ID/staff/manager" \
  "$(jq -nc --arg id "$MANAGER_USER_ID" '{userId:$id}')" >/dev/null
request_status 403 "$MANAGER_TOKEN" DELETE "/tournaments/$TOURNAMENT_ID/staff/manager/$MANAGER_USER_ID" >/dev/null

echo "[5/7] Owner revokes delegated access"
request_status 204 "$OWNER_TOKEN" DELETE "/tournaments/$TOURNAMENT_ID/staff/manager/$MANAGER_USER_ID" >/dev/null

echo "[6/7] Verify revocation takes effect immediately"
request_status 404 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID" >/dev/null
request_status 404 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/staff" >/dev/null
request_status 404 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/fixtures" >/dev/null
request_status 404 "$MANAGER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/analytics" >/dev/null
assert_mine_contains "$MANAGER_TOKEN" false

echo "[7/7] Verify owner retains access and assignment list is clean"
OWNER_STAFF="$(request_status 200 "$OWNER_TOKEN" GET "/tournaments/$TOURNAMENT_ID/staff")"
jq -e '.canManage == true and ([.managers[] | select(.role == "MANAGER")] | length == 0)' \
  <<<"$OWNER_STAFF" >/dev/null || { echo "FAIL: revoked manager remains assigned" >&2; exit 1; }
request_status 200 "$OWNER_TOKEN" GET "/tournaments/$TOURNAMENT_ID" >/dev/null

echo
echo "=== TOURNAMENT DELEGATED-MANAGER ACCESS E2E PASSED ==="
echo "Grant, idempotent assignment, scoped access, privilege boundaries and immediate revocation verified."
