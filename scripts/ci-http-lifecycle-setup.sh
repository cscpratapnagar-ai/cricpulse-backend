#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
RUN_KEY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
RUN_DIGITS="${GITHUB_RUN_ID:-1234567}"
RUN_DIGITS="${RUN_DIGITS: -7}"
PASSWORD="${E2E_PASSWORD:-$(openssl rand -hex 16)A!}"
OWNER_A_EMAIL="cricpulse-ci-owner-a-${RUN_KEY}@example.com"
PLAYER_A_EMAIL="cricpulse-ci-player-a-${RUN_KEY}@example.com"
OWNER_B_EMAIL="cricpulse-ci-owner-b-${RUN_KEY}@example.com"
PLAYER_B_EMAIL="cricpulse-ci-player-b-${RUN_KEY}@example.com"
OWNER_A_PHONE="900${RUN_DIGITS}"
PLAYER_A_PHONE="901${RUN_DIGITS}"
OWNER_B_PHONE="902${RUN_DIGITS}"
PLAYER_B_PHONE="903${RUN_DIGITS}"

curl_json() { curl -fsS -X "$1" "$2" -H 'Content-Type: application/json' ${3:+--data "$3"}; }
register() { curl_json POST "$BASE_URL/users" "$(jq -nc --arg n "$1" --arg e "$2" --arg p "$3" --arg pw "$PASSWORD" '{fullName:$n,email:$e,phone:$p,password:$pw}')" >/dev/null; }

register 'CricPulse CI Owner A' "$OWNER_A_EMAIL" "$OWNER_A_PHONE"
register 'CricPulse CI Player A' "$PLAYER_A_EMAIL" "$PLAYER_A_PHONE"
register 'CricPulse CI Owner B' "$OWNER_B_EMAIL" "$OWNER_B_PHONE"
register 'CricPulse CI Player B' "$PLAYER_B_EMAIL" "$PLAYER_B_PHONE"

login_token() { curl_json POST "$BASE_URL/auth/login" "$(jq -nc --arg e "$1" --arg p "$PASSWORD" '{email:$e,password:$p}')" | jq -r '.accessToken'; }
OWNER_A_TOKEN="$(login_token "$OWNER_A_EMAIL")"; OWNER_B_TOKEN="$(login_token "$OWNER_B_EMAIL")"
test -n "$OWNER_A_TOKEN" && test "$OWNER_A_TOKEN" != null; test -n "$OWNER_B_TOKEN" && test "$OWNER_B_TOKEN" != null
AUTH_A=(-H "Authorization: Bearer $OWNER_A_TOKEN"); AUTH_B=(-H "Authorization: Bearer $OWNER_B_TOKEN")

OWNER_A_PROFILE="$(curl -fsS "${AUTH_A[@]}" "$BASE_URL/players/me")"
PLAYER_A_PROFILE="$(curl -fsS -H "Authorization: Bearer $(login_token "$PLAYER_A_EMAIL")" "$BASE_URL/players/me")"
OWNER_B_PROFILE="$(curl -fsS "${AUTH_B[@]}" "$BASE_URL/players/me")"
PLAYER_B_PROFILE="$(curl -fsS -H "Authorization: Bearer $(login_token "$PLAYER_B_EMAIL")" "$BASE_URL/players/me")"
OWNER_A_PLAYER_ID="$(jq -r '.id' <<<"$OWNER_A_PROFILE")"; PLAYER_A_ID="$(jq -r '.id' <<<"$PLAYER_A_PROFILE")"
OWNER_B_PLAYER_ID="$(jq -r '.id' <<<"$OWNER_B_PROFILE")"; PLAYER_B_ID="$(jq -r '.id' <<<"$PLAYER_B_PROFILE")"

test -n "$OWNER_A_PLAYER_ID" && test "$OWNER_A_PLAYER_ID" != null; test -n "$PLAYER_A_ID" && test "$PLAYER_A_ID" != null
test -n "$OWNER_B_PLAYER_ID" && test "$OWNER_B_PLAYER_ID" != null; test -n "$PLAYER_B_ID" && test "$PLAYER_B_ID" != null

TEAM_A="$(curl -fsS -X POST "$BASE_URL/teams" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg n "CricPulse CI A ${RUN_KEY}" '{name:$n,city:"CI"}')")"; TEAM_A_ID="$(jq -r '.id' <<<"$TEAM_A")"
TEAM_B="$(curl -fsS -X POST "$BASE_URL/teams" "${AUTH_B[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg n "CricPulse CI B ${RUN_KEY}" '{name:$n,city:"CI"}')")"; TEAM_B_ID="$(jq -r '.id' <<<"$TEAM_B")"
test -n "$TEAM_A_ID" && test -n "$TEAM_B_ID"

# Ownership grants management authority, but every player selected into a
# Playing XI must also be an explicit member of that team's roster.
curl -fsS -X POST "$BASE_URL/teams/$TEAM_A_ID/members" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg p "$OWNER_A_PLAYER_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null
curl -fsS -X POST "$BASE_URL/teams/$TEAM_A_ID/members" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg p "$PLAYER_A_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null
curl -fsS -X POST "$BASE_URL/teams/$TEAM_B_ID/members" "${AUTH_B[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg p "$OWNER_B_PLAYER_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null
curl -fsS -X POST "$BASE_URL/teams/$TEAM_B_ID/members" "${AUTH_B[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg p "$PLAYER_B_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null

MATCH="$(curl -fsS -X POST "$BASE_URL/matches" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg n "CricPulse CI Lifecycle ${RUN_KEY}" --arg a "$TEAM_A_ID" --arg b "$TEAM_B_ID" '{name:$n,teamAId:$a,teamBId:$b,format:"T20",totalOvers:20}')")"; MATCH_ID="$(jq -r '.id' <<<"$MATCH")"
test -n "$MATCH_ID" && test "$MATCH_ID" != null
echo "Created fixture match=$MATCH_ID teamA=$TEAM_A_ID teamB=$TEAM_B_ID"

select_xi() { local auth_name="$1" team_id="$2" player_id="$3"; local -n auth_ref="$auth_name"; curl -fsS -X POST "$BASE_URL/matches/$MATCH_ID/playing-xi" "${auth_ref[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg t "$team_id" --arg p "$player_id" '{teamId:$t,playerId:$p,captain:false,viceCaptain:false,wicketKeeper:false}')" >/dev/null; }
select_xi AUTH_A "$TEAM_A_ID" "$OWNER_A_PLAYER_ID"; select_xi AUTH_A "$TEAM_A_ID" "$PLAYER_A_ID"; select_xi AUTH_B "$TEAM_B_ID" "$OWNER_B_PLAYER_ID"; select_xi AUTH_B "$TEAM_B_ID" "$PLAYER_B_ID"
echo "Selected Playing XI for both teams"

cat >> "$GITHUB_ENV" <<EOF
E2E_EMAIL=$OWNER_A_EMAIL
E2E_PASSWORD=$PASSWORD
E2E_TEAM_A_ID=$TEAM_A_ID
E2E_TEAM_B_ID=$TEAM_B_ID
E2E_OWNER_PLAYER_ID=$OWNER_A_PLAYER_ID
E2E_PLAYER_ID=$PLAYER_A_ID
E2E_B_OWNER_PLAYER_ID=$OWNER_B_PLAYER_ID
E2E_B_PLAYER_ID=$PLAYER_B_ID
E2E_MATCH_ID=$MATCH_ID
EOF
