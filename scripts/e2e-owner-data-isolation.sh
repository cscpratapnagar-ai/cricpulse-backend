#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
OWNER_A_EMAIL="${E2E_EMAIL:?E2E_EMAIL is required}"
OWNER_B_EMAIL="${E2E_OWNER_B_EMAIL:?E2E_OWNER_B_EMAIL is required}"
PASSWORD="${E2E_PASSWORD:?E2E_PASSWORD is required}"
TEAM_A_ID="${E2E_TEAM_A_ID:?E2E_TEAM_A_ID is required}"
TEAM_B_ID="${E2E_TEAM_B_ID:?E2E_TEAM_B_ID is required}"
PLAYER_B_ID="${E2E_B_OWNER_PLAYER_ID:?E2E_B_OWNER_PLAYER_ID is required}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
BODY_FILE="$TMP_DIR/body"

login_token() {
  curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg email "$1" --arg password "$PASSWORD" '{email:$email,password:$password}')" |
    jq -er '.accessToken // .token // .data.accessToken'
}
TOKEN_A="$(login_token "$OWNER_A_EMAIL")"
TOKEN_B="$(login_token "$OWNER_B_EMAIL")"
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
echo "PASS: owner data-isolation HTTP regression"
