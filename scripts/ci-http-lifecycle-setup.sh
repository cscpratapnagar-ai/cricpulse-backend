#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
RUN_KEY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
RUN_DIGITS="${GITHUB_RUN_ID:-1234567}"
RUN_DIGITS="${RUN_DIGITS: -7}"
PASSWORD="${E2E_PASSWORD:-$(openssl rand -hex 16)A!}"
OWNER_EMAIL="cricpulse-ci-owner-${RUN_KEY}@example.com"
PLAYER_EMAIL="cricpulse-ci-player-${RUN_KEY}@example.com"
OWNER_PHONE="900${RUN_DIGITS}"
PLAYER_PHONE="901${RUN_DIGITS}"

curl_json() {
  curl -fsS -X "$1" "$2" -H 'Content-Type: application/json' ${3:+--data "$3"}
}

register() {
  curl_json POST "$BASE_URL/users" "$(jq -nc \
    --arg n "$1" --arg e "$2" --arg p "$3" --arg pw "$PASSWORD" \
    '{fullName:$n,email:$e,phone:$p,password:$pw}')" >/dev/null
}

register 'CricPulse CI Owner' "$OWNER_EMAIL" "$OWNER_PHONE"
register 'CricPulse CI Player' "$PLAYER_EMAIL" "$PLAYER_PHONE"

LOGIN="$(curl_json POST "$BASE_URL/auth/login" "$(jq -nc --arg e "$OWNER_EMAIL" --arg p "$PASSWORD" '{email:$e,password:$p}')")"
TOKEN="$(jq -r '.accessToken' <<<"$LOGIN")"
test -n "$TOKEN" && test "$TOKEN" != null
AUTH=(-H "Authorization: Bearer $TOKEN")

OWNER_PROFILE="$(curl -fsS "${AUTH[@]}" "$BASE_URL/players/me")"
OWNER_PLAYER_ID="$(jq -r '.id' <<<"$OWNER_PROFILE")"
PLAYER_LOGIN="$(curl_json POST "$BASE_URL/auth/login" "$(jq -nc --arg e "$PLAYER_EMAIL" --arg p "$PASSWORD" '{email:$e,password:$p}')")"
PLAYER_TOKEN="$(jq -r '.accessToken' <<<"$PLAYER_LOGIN")"
PLAYER_PROFILE="$(curl -fsS -H "Authorization: Bearer $PLAYER_TOKEN" "$BASE_URL/players/me")"
PLAYER_ID="$(jq -r '.id' <<<"$PLAYER_PROFILE")"

test -n "$OWNER_PLAYER_ID" && test "$OWNER_PLAYER_ID" != null
test -n "$PLAYER_ID" && test "$PLAYER_ID" != null

TEAM_A="$(curl -fsS -X POST "$BASE_URL/teams" "${AUTH[@]}" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg n "CricPulse CI A ${RUN_KEY}" '{name:$n,city:"CI"}')")"
TEAM_B="$(curl -fsS -X POST "$BASE_URL/teams" "${AUTH[@]}" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg n "CricPulse CI B ${RUN_KEY}" '{name:$n,city:"CI"}')")"
TEAM_A_ID="$(jq -r '.id' <<<"$TEAM_A")"
TEAM_B_ID="$(jq -r '.id' <<<"$TEAM_B")"

test -n "$TEAM_A_ID" && test -n "$TEAM_B_ID"

# The disposable CI owner must be able to manage the Playing XI for both
# sides. This keeps the HTTP fixture self-contained without weakening the
# production Playing XI authorization rule.
for team_id in "$TEAM_A_ID" "$TEAM_B_ID"; do
  curl -fsS -X POST "$BASE_URL/teams/$team_id/members" "${AUTH[@]}" \
    -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg p "$OWNER_PLAYER_ID" '{playerId:$p,role:"CAPTAIN"}')" >/dev/null
  curl -fsS -X POST "$BASE_URL/teams/$team_id/members" "${AUTH[@]}" \
    -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg p "$PLAYER_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null
done

# Create the disposable match here so the fixture can be fully prepared
# through public HTTP APIs before the scoring E2E starts. Playing XI is a
# real match prerequisite and must remain enforced by production code.
MATCH="$(curl -fsS -X POST "$BASE_URL/matches" "${AUTH[@]}" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg n "CricPulse CI Lifecycle ${RUN_KEY}" --arg a "$TEAM_A_ID" --arg b "$TEAM_B_ID" \
    '{name:$n,teamAId:$a,teamBId:$b,format:"T20",totalOvers:20}')")"
MATCH_ID="$(jq -r '.id' <<<"$MATCH")"
test -n "$MATCH_ID" && test "$MATCH_ID" != null

select_xi() {
  local team_id="$1" player_id="$2"
  curl -fsS -X POST "$BASE_URL/matches/$MATCH_ID/playing-xi" "${AUTH[@]}" \
    -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg t "$team_id" --arg p "$player_id" \
      '{teamId:$t,playerId:$p,captain:false,viceCaptain:false,wicketKeeper:false}')" >/dev/null
done

select_xi "$TEAM_A_ID" "$OWNER_PLAYER_ID"
select_xi "$TEAM_A_ID" "$PLAYER_ID"
select_xi "$TEAM_B_ID" "$OWNER_PLAYER_ID"
select_xi "$TEAM_B_ID" "$PLAYER_ID"

cat >> "$GITHUB_ENV" <<EOF
E2E_EMAIL=$OWNER_EMAIL
E2E_PASSWORD=$PASSWORD
E2E_TEAM_A_ID=$TEAM_A_ID
E2E_TEAM_B_ID=$TEAM_B_ID
E2E_OWNER_PLAYER_ID=$OWNER_PLAYER_ID
E2E_PLAYER_ID=$PLAYER_ID
E2E_MATCH_ID=$MATCH_ID
EOF

echo "Prepared disposable HTTP E2E teams, player profiles, match and Playing XI."
