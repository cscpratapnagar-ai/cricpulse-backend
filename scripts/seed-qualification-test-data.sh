#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
OWNER_EMAIL="${OWNER_EMAIL:-rahul.test2026@gmail.com}"
OWNER_PASSWORD="${OWNER_PASSWORD:-Test@12345}"
OWNER_NAME="${OWNER_NAME:-Rahul Test}"
OWNER_PHONE="${OWNER_PHONE:-9876543211}"
SUFFIX="${SUFFIX:-$(date +%Y%m%d-%H%M%S)}"
PASSWORD="${TEST_PASSWORD:-Test@12345}"

command -v curl >/dev/null || { echo "curl is required"; exit 1; }
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

json() {
  curl -sS --fail-with-body -H "Content-Type: application/json" "$@"
}

echo "== CricPulse Qualification Test Data Seeder =="
echo "API: $BASE_URL"
echo "Suffix: $SUFFIX"

owner_register='{"fullName":"'"$OWNER_NAME"'","email":"'"$OWNER_EMAIL"'","phone":"'"$OWNER_PHONE"'","password":"'"$OWNER_PASSWORD"'"}'
if ! json -X POST "$BASE_URL/users" -d "$owner_register" >/tmp/cricpulse_owner_register.json 2>/tmp/cricpulse_owner_register.err; then
  echo "Owner registration skipped (account may already exist)."
fi

owner_login="$(json -X POST "$BASE_URL/auth/login" -d '{"email":"'"$OWNER_EMAIL"'","password":"'"$OWNER_PASSWORD"'"}')"
OWNER_TOKEN="$(jq -r '.accessToken' <<<"$owner_login")"
[[ -n "$OWNER_TOKEN" && "$OWNER_TOKEN" != "null" ]] || { echo "$owner_login"; exit 1; }
AUTH=(-H "Authorization: Bearer $OWNER_TOKEN")

tournament_payload='{"name":"Rahul Qualification Test '"$SUFFIX"'","format":"T20","overs":20,"location":"Pratapnagar","startDate":"2026-08-20"}'
tournament="$(json -X POST "$BASE_URL/tournaments" "${AUTH[@]}" -d "$tournament_payload")"
TOURNAMENT_ID="$(jq -r '.id' <<<"$tournament")"
[[ -n "$TOURNAMENT_ID" && "$TOURNAMENT_ID" != "null" ]] || { echo "$tournament"; exit 1; }
echo "Tournament: $TOURNAMENT_ID"

declare -a TEAM_IDS
declare -a TEAM_NAMES=("Rahul Warriors" "Pratap Kings" "Test Strikers" "Digital Challengers")

for name in "${TEAM_NAMES[@]}"; do
  team_payload='{"name":"'"$name"' '"$SUFFIX"'","city":"Pratapnagar"}'
  team="$(json -X POST "$BASE_URL/teams" "${AUTH[@]}" -d "$team_payload")"
  team_id="$(jq -r '.id' <<<"$team")"
  [[ -n "$team_id" && "$team_id" != "null" ]] || { echo "$team"; exit 1; }
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
    register_payload='{"fullName":"'"$full_name"'","email":"'"$email"'","phone":"'"$phone"'","password":"'"$PASSWORD"'"}'
    if ! json -X POST "$BASE_URL/users" -d "$register_payload" >/dev/null 2>/tmp/cricpulse_player_register.err; then
      echo "Player registration skipped: $email"
    fi
    member_payload='{"email":"'"$email"'","role":"PLAYER"}'
    json -X POST "$BASE_URL/teams/$team_id/members" "${AUTH[@]}" -d "$member_payload" >/dev/null
  done
  echo "Added 11 players to $team_name"
done

for team_id in "${TEAM_IDS[@]}"; do
  json -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/teams/$team_id" "${AUTH[@]}" -d '{}' >/dev/null
done

fixtures="$(json -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/generate" "${AUTH[@]}" -d '{}')"
generated="$(jq -r '.generated // 0' <<<"$fixtures")"

echo
echo "=== SEED COMPLETE ==="
echo "Tournament ID : $TOURNAMENT_ID"
echo "Tournament URL: http://localhost:4200/tournaments/$TOURNAMENT_ID"
echo "Qualification : http://localhost:4200/tournaments/$TOURNAMENT_ID/qualification"
echo "Schedule      : http://localhost:4200/tournaments/$TOURNAMENT_ID/schedule"
echo "Fixtures      : $generated generated"
echo
echo "Fixtures are created but NOT completed."
echo "Complete matches through the existing scoring engine to generate points/NRR."
echo "Test login: $OWNER_EMAIL / $OWNER_PASSWORD"
