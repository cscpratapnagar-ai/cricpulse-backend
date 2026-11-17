#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
OWNER_A_EMAIL="${E2E_EMAIL:?E2E_EMAIL is required}"
OWNER_B_EMAIL="${E2E_OWNER_B_EMAIL:?E2E_OWNER_B_EMAIL is required}"
PASSWORD="${E2E_PASSWORD:?E2E_PASSWORD is required}"
TEAM_A_ID="${E2E_TEAM_A_ID:?E2E_TEAM_A_ID is required}"
TEAM_B_ID="${E2E_TEAM_B_ID:?E2E_TEAM_B_ID is required}"
PLAYER_B_ID="${E2E_B_OWNER_PLAYER_ID:?E2E_B_OWNER_PLAYER_ID is required}"
PRIVATE_MATCH_ID="${E2E_PRIVATE_MATCH_ID:?E2E_PRIVATE_MATCH_ID is required}"
PRIVATE_TEAM_B2_ID="${E2E_PRIVATE_TEAM_B2_ID:?E2E_PRIVATE_TEAM_B2_ID is required}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
BODY_FILE="$TMP_DIR/body"

# Login now uses an HttpOnly cookie and no longer returns a JWT in JSON.
# Use an isolated cookie jar per owner and submit the matching CSRF cookie/header.
login_token() {
  local email="$1" jar="$2" csrf token
  curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" >/dev/null
  csrf="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$jar")"
  [[ -n "$csrf" ]] || { echo "CSRF cookie missing for $email" >&2; return 1; }
  curl -fsS -b "$jar" -c "$jar" -X POST "$BASE_URL/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
    --data "$(jq -nc --arg email "$email" --arg password "$PASSWORD" '{email:$email,password:$password}')" >/dev/null
  token="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$jar")"
  [[ -n "$token" ]] || { echo "HttpOnly access cookie missing after login for $email" >&2; return 1; }
  printf '%s' "$token"
}
JAR_A="$TMP_DIR/owner-a.cookies"
JAR_B="$TMP_DIR/owner-b.cookies"
TOKEN_A="$(login_token "$OWNER_A_EMAIL" "$JAR_A")"
TOKEN_B="$(login_token "$OWNER_B_EMAIL" "$JAR_B")"
[[ -n "$TOKEN_A" && -n "$TOKEN_B" ]]

request_status() {
  local token="$1" method="$2" path="$3" status
  status="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $token")"
  printf '%s' "$status"
}
expect_forbidden() {
  local label="$1" token="$2" path="$3" status
  status="$(request_status "$token" GET "$path")"
  if [[ "$status" != "403" ]]; then
    echo "FAIL: $label expected HTTP 403, received $status" >&2
    cat "$BODY_FILE" >&2 || true
    exit 1
  fi
  echo "PASS: $label (HTTP 403)"
}
assert_mine_only() {
  local label="$1" token="$2" own_team="$3" other_team="$4" response
  response="$(curl -fsS "$BASE_URL/teams/mine" -H "Authorization: Bearer $token")"
  jq -e --arg own "$own_team" --arg other "$other_team" \
    '([.[] | .id] | index($own) != null) and ([.[] | .id] | index($other) == null)' \
    <<<"$response" >/dev/null || {
      echo "FAIL: $label returned missing own team or another user's team" >&2
      jq -c '[.[] | {id,name}]' <<<"$response" >&2 || true
      exit 1
    }
  echo "PASS: $label only returns the authenticated owner's teams"
}

echo "[1/3] Verify owner-scoped team lists"
assert_mine_only "Owner A /teams/mine" "$TOKEN_A" "$TEAM_A_ID" "$TEAM_B_ID"
assert_mine_only "Owner B /teams/mine" "$TOKEN_B" "$TEAM_B_ID" "$TEAM_A_ID"

echo "[2/3] Verify cross-owner team endpoints are denied"
expect_forbidden "Owner A cannot read Team B" "$TOKEN_A" "/teams/$TEAM_B_ID"
expect_forbidden "Owner A cannot read Team B members" "$TOKEN_A" "/teams/$TEAM_B_ID/members"
expect_forbidden "Owner A cannot read Team B players" "$TOKEN_A" "/teams/$TEAM_B_ID/players"
expect_forbidden "Owner A cannot read Team B access" "$TOKEN_A" "/teams/$TEAM_B_ID/access"
expect_forbidden "Owner B cannot read Team A" "$TOKEN_B" "/teams/$TEAM_A_ID"
expect_forbidden "Owner B cannot read Team A members" "$TOKEN_B" "/teams/$TEAM_A_ID/members"
expect_forbidden "Owner B cannot read Team A players" "$TOKEN_B" "/teams/$TEAM_A_ID/players"
expect_forbidden "Owner B cannot read Team A access" "$TOKEN_B" "/teams/$TEAM_A_ID/access"

echo "[3/3] Verify cross-owner player profile is denied"
expect_forbidden "Owner A cannot read Owner B player profile" "$TOKEN_A" "/players/$PLAYER_B_ID"
echo "[4/4] Verify cross-owner match endpoints are denied"
expect_forbidden "Owner A cannot read Owner B private match" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID"
expect_forbidden "Owner A cannot open Owner B private broadcast controls" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID/broadcast-access"
expect_forbidden "Owner A cannot read Owner B private toss setup" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID/toss"
expect_forbidden "Owner A cannot read Owner B private scorecard" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID/scorecard"
expect_forbidden "Owner A cannot read Owner B private current innings" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID/current-innings"
expect_forbidden "Owner A cannot read Owner B private playing XI" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID/playing-xi"
expect_forbidden "Owner A cannot read Owner B private team squad" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID/teams/$PRIVATE_TEAM_B2_ID/members"
expect_forbidden "Owner A cannot read Owner B private match result" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID/result"
expect_forbidden "Owner A cannot read Owner B private match analytics" "$TOKEN_A" "/matches/$PRIVATE_MATCH_ID/analytics"
echo "PASS: owner data-isolation HTTP regression"
