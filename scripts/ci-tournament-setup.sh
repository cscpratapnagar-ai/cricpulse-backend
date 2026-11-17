#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
RUN_KEY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"

[[ -n "$EMAIL" && -n "$PASSWORD" ]] || { echo "ERROR: EMAIL and PASSWORD are required" >&2; exit 2; }
command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT
curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE_URL/auth/csrf" >/dev/null
CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$COOKIE_JAR")"
[[ -n "$CSRF_TOKEN" ]] || { echo "ERROR: CSRF cookie missing" >&2; exit 1; }
LOGIN_STATUS="$(curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -o /tmp/cricpulse-tournament-login.json -w '%{http_code}' -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF_TOKEN" --data "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
[[ "$LOGIN_STATUS" == "200" ]] || { echo "ERROR: login HTTP $LOGIN_STATUS" >&2; cat /tmp/cricpulse-tournament-login.json >&2; exit 1; }
TOKEN="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$COOKIE_JAR")"
[[ -n "$TOKEN" ]] || { echo "ERROR: HttpOnly access cookie missing" >&2; exit 1; }
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

echo "Tournament fixture setup complete: tournament=$TOURNAMENT_ID teams=$TEAM_A_ID,$TEAM_B_ID,$TEAM_C_ID"

echo "Fixture generation is intentionally left to the hardening E2E so its first generation call can verify creation counters."

cat >> "$GITHUB_ENV" <<EOF
E2E_TOURNAMENT_ID=$TOURNAMENT_ID
EOF
