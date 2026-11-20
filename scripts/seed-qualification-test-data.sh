#!/usr/bin/env bash
set -euo pipefail

# Local-only qualification/playoff UI fixture seeder.
# Creates a reusable owner login, four teams, 44 player accounts and a draft tournament.
# Never point this script at production. Use a disposable local database.

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
OWNER_EMAIL="${OWNER_EMAIL:-rahul.test2026@gmail.com}"
OWNER_PASSWORD="${OWNER_PASSWORD:-Test@12345}"
OWNER_NAME="${OWNER_NAME:-Rahul Test}"
OWNER_PHONE="${OWNER_PHONE:-9876543211}"
SUFFIX="${SUFFIX:-$(date +%Y%m%d-%H%M%S)}"
TEST_PASSWORD="${TEST_PASSWORD:-Test@12345}"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

COOKIE_JAR="$(mktemp)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"; rm -f "$COOKIE_JAR"' EXIT

api() {
  curl -sS --fail-with-body -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$@"
}

echo "== CricPulse Qualification Test Data Seeder =="
echo "API: $BASE_URL"
echo "Suffix: $SUFFIX"
echo "Safety: local/disposable environment only"

owner_register="$(jq -nc --arg n "$OWNER_NAME" --arg e "$OWNER_EMAIL" --arg p "$OWNER_PHONE" --arg pw "$OWNER_PASSWORD" \
  '{fullName:$n,email:$e,phone:$p,password:$pw}')"
if ! api -X POST "$BASE_URL/users" -H "Content-Type: application/json" -d "$owner_register" \
  >"$TMP_DIR/register.json" 2>"$TMP_DIR/register.err"; then
  echo "Owner registration skipped (account may already exist)."
fi

# Current browser authentication contract: bootstrap CSRF, login, then read the
# HttpOnly access cookie. The login JSON intentionally does not return a bearer token.
api "$BASE_URL/auth/csrf" >/dev/null
CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$COOKIE_JAR")"
[[ -n "$CSRF_TOKEN" ]] || { echo "ERROR: CSRF token missing" >&2; exit 1; }
LOGIN_STATUS="$(curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -o "$TMP_DIR/login.json" -w '%{http_code}' \
  -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
  --data "$(jq -nc --arg email "$OWNER_EMAIL" --arg password "$OWNER_PASSWORD" '{email:$email,password:$password}')")"
[[ "$LOGIN_STATUS" == "200" ]] || {
  echo "ERROR: owner login returned HTTP $LOGIN_STATUS. If this account already exists, set OWNER_EMAIL and OWNER_PASSWORD to a matching local test account." >&2
  cat "$TMP_DIR/login.json" >&2
  exit 1
}
TOKEN="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$COOKIE_JAR")"
[[ -n "$TOKEN" ]] || { echo "ERROR: HttpOnly access cookie missing" >&2; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

json() {
  curl -sS --fail-with-body -H "Content-Type: application/json" "${AUTH[@]}" "$@"
}

TOURNAMENT="$(json -X POST "$BASE_URL/tournaments" -d "$(jq -nc --arg n "Rahul Qualification Test $SUFFIX" '{name:$n,format:"T20",overs:20,location:"Pratapnagar",startDate:null}')")"
TOURNAMENT_ID="$(jq -r '.id // empty' <<<"$TOURNAMENT")"
[[ -n "$TOURNAMENT_ID" ]] || { echo "ERROR: tournament creation failed: $TOURNAMENT" >&2; exit 1; }
echo "Tournament: $TOURNAMENT_ID"

declare -a TEAM_IDS
declare -a TEAM_NAMES=("Rahul Warriors" "Pratap Kings" "Test Strikers" "Digital Challengers")

for name in "${TEAM_NAMES[@]}"; do
  team="$(json -X POST "$BASE_URL/teams" -d "$(jq -nc --arg n "$name $SUFFIX" '{name:$n,city:"Pratapnagar"}')")"
  team_id="$(jq -r '.id // empty' <<<"$team")"
  [[ -n "$team_id" ]] || { echo "ERROR: team creation failed: $team" >&2; exit 1; }
  TEAM_IDS+=("$team_id")
  echo "Team: $name -> $team_id"
done

for team_index in 0 1 2 3; do
  team_id="${TEAM_IDS[$team_index]}"
  team_name="${TEAM_NAMES[$team_index]}"
  for player_index in $(seq 1 11); do
    code="$(printf '%02d' "$player_index")"
    email_prefix="$(echo "$team_name" | tr '[:upper:] ' '[:lower:]_' | tr -cd '[:alnum:]_')"
    email="${email_prefix}.${SUFFIX}.${code}@example.com"
    phone="700000$(printf '%04d' $((team_index * 11 + player_index)))"
    full_name="${team_name} Player ${code}"
    register_payload="$(jq -nc --arg n "$full_name" --arg e "$email" --arg p "$phone" --arg pw "$TEST_PASSWORD" \
      '{fullName:$n,email:$e,phone:$p,password:$pw}')"
    if ! api -X POST "$BASE_URL/users" -H "Content-Type: application/json" -d "$register_payload" \
      >"$TMP_DIR/player-register.json" 2>"$TMP_DIR/player-register.err"; then
      echo "Player registration skipped: $email"
    fi
    member_payload="$(jq -nc --arg e "$email" '{email:$e,role:"PLAYER"}')"
    json -X POST "$BASE_URL/teams/$team_id/members" -d "$member_payload" >/dev/null
  done
  echo "Added 11 players to $team_name"
done

for team_id in "${TEAM_IDS[@]}"; do
  json -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/teams/$team_id" -d '{}' >/dev/null
done

fixtures="$(json -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/generate" -d '{}')"
generated="$(jq -r '.generated // 0' <<<"$fixtures")"

echo
echo "=== SEED COMPLETE ==="
echo "Tournament ID : $TOURNAMENT_ID"
echo "Tournament URL: http://localhost:4200/tournaments/$TOURNAMENT_ID"
echo "Public URL    : http://localhost:4200/tournament/$TOURNAMENT_ID"
echo "Qualification : http://localhost:4200/tournaments/$TOURNAMENT_ID/qualification"
echo "Schedule      : http://localhost:4200/tournaments/$TOURNAMENT_ID/schedule"
echo "Fixtures      : $generated generated"
echo
echo "Owner UI login: $OWNER_EMAIL / $OWNER_PASSWORD"
echo "Player password: $TEST_PASSWORD (emails are printed in the API/DB seed records)"
echo "Tournament remains DRAFT; fixtures are SCHEDULED. Activate it in the organizer UI after review."
echo "Complete matches through the existing scoring engine to generate points/NRR."
