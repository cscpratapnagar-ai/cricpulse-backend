#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
RUN_KEY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"

[[ -n "$EMAIL" && -n "$PASSWORD" ]] || { echo "ERROR: EMAIL and PASSWORD are required" >&2; exit 2; }
command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

login_payload="$(jq -cn --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')"
login_response="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' --data "$login_payload")"
TOKEN="$(jq -r '.token // .accessToken // .data.token // empty' <<<"$login_response")"
[[ -n "$TOKEN" ]] || { echo "ERROR: login response did not contain a JWT" >&2; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

create_team() {
  curl -fsS -X POST "$BASE_URL/teams" "${AUTH[@]}" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg n "$1" '{name:$n,city:"CI"}')" | jq -r '.id'
}

TEAM_A_ID="$(create_team "CricPulse CI Tournament A ${RUN_KEY}")"
TEAM_B_ID="$(create_team "CricPulse CI Tournament B ${RUN_KEY}")"
TEAM_C_ID="$(create_team "CricPulse CI Tournament C ${RUN_KEY}")"
[[ -n "$TEAM_A_ID" && -n "$TEAM_B_ID" && -n "$TEAM_C_ID" ]] || { echo "ERROR: failed to create tournament teams" >&2; exit 1; }

TOURNAMENT="$(curl -fsS -X POST "$BASE_URL/tournaments" "${AUTH[@]}" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg n "CricPulse CI Tournament ${RUN_KEY}" '{name:$n,format:"T20",overs:20,location:"CI",startDate:null}')")"
TOURNAMENT_ID="$(jq -r '.id' <<<"$TOURNAMENT")"
[[ -n "$TOURNAMENT_ID" && "$TOURNAMENT_ID" != null ]] || { echo "ERROR: tournament creation failed" >&2; exit 1; }

for team_id in "$TEAM_A_ID" "$TEAM_B_ID" "$TEAM_C_ID"; do
  curl -fsS -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/teams/$team_id" "${AUTH[@]}" >/dev/null
done

generate="$(curl -fsS -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/generate" "${AUTH[@]}" -H 'Accept: application/json')"
fixture_count="$(jq '.fixtures | length' <<<"$generate")"
FIXTURE_A_ID="$(jq -r '.fixtures[0].matchId // empty' <<<"$generate")"
FIXTURE_B_ID="$(jq -r '.fixtures[1].matchId // empty' <<<"$generate")"
[[ "$fixture_count" -eq 3 && -n "$FIXTURE_A_ID" && -n "$FIXTURE_B_ID" ]] || {
  echo "ERROR: expected 3 generated fixtures, got $fixture_count" >&2
  exit 1
}

echo "Tournament fixture setup complete: tournament=$TOURNAMENT_ID fixtures=$FIXTURE_A_ID,$FIXTURE_B_ID"

cat >> "$GITHUB_ENV" <<EOF
E2E_TOURNAMENT_ID=$TOURNAMENT_ID
E2E_FIXTURE_A_ID=$FIXTURE_A_ID
E2E_FIXTURE_B_ID=$FIXTURE_B_ID
EOF
