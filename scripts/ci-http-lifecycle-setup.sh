#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
RUN_KEY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
RUN_DIGITS="${GITHUB_RUN_ID:-1234567}"
RUN_DIGITS="${RUN_DIGITS: -7}"
PASSWORD="${E2E_PASSWORD:-$(openssl rand -hex 16)A!}"
OWNER_A_EMAIL="cricpulse-ci-owner-a-${RUN_KEY}@example.com"
PLAYER_A_EMAIL="cricpulse-ci-player-a-${RUN_KEY}@example.com"
PLAYER_A2_EMAIL="cricpulse-ci-player-a2-${RUN_KEY}@example.com"
PLAYER_A3_EMAIL="cricpulse-ci-player-a3-${RUN_KEY}@example.com"
OWNER_B_EMAIL="cricpulse-ci-owner-b-${RUN_KEY}@example.com"
PLAYER_B_EMAIL="cricpulse-ci-player-b-${RUN_KEY}@example.com"
OWNER_A_PHONE="900${RUN_DIGITS}"
PLAYER_A_PHONE="901${RUN_DIGITS}"
PLAYER_A2_PHONE="906${RUN_DIGITS}"
PLAYER_A3_PHONE="905${RUN_DIGITS}"
OWNER_B_PHONE="902${RUN_DIGITS}"
PLAYER_B_PHONE="903${RUN_DIGITS}"
COOKIE_JAR="$(mktemp)"
COOKIE_AUTH_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR" "$COOKIE_AUTH_JAR"' EXIT
curl_api() {
  local response_file status
  response_file="$(mktemp)"
  status="$(curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF_TOKEN" -o "$response_file" -w '%{http_code}' "$@")"
  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    echo "HTTP $status for request: $*" >&2
    cat "$response_file" >&2
    rm -f "$response_file"
    return 22
  fi
  cat "$response_file"
  rm -f "$response_file"
}
# Bootstrap the CSRF cookie, then use the exact value persisted by curl's cookie jar.
CSRF_RESPONSE="$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE_URL/auth/csrf")"
CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { token=$7 } END { print token }' "$COOKIE_JAR")"
if [[ -z "$CSRF_TOKEN" ]]; then
  echo "CSRF bootstrap did not persist XSRF-TOKEN. Response: $CSRF_RESPONSE" >&2
  cat "$COOKIE_JAR" >&2
  exit 1
fi
curl_json() { curl_api -X "$1" "$2" -H 'Content-Type: application/json' ${3:+--data "$3"}; }
register() { curl_json POST "$BASE_URL/users" "$(jq -nc --arg n "$1" --arg e "$2" --arg p "$3" --arg pw "$PASSWORD" '{fullName:$n,email:$e,phone:$p,password:$pw}')" >/dev/null; }

register 'CricPulse CI Owner A' "$OWNER_A_EMAIL" "$OWNER_A_PHONE"
register 'CricPulse CI Player A' "$PLAYER_A_EMAIL" "$PLAYER_A_PHONE"
register 'CricPulse CI Player A2' "$PLAYER_A2_EMAIL" "$PLAYER_A2_PHONE"
register 'CricPulse CI Player A3' "$PLAYER_A3_EMAIL" "$PLAYER_A3_PHONE"
register 'CricPulse CI Owner B' "$OWNER_B_EMAIL" "$OWNER_B_PHONE"
register 'CricPulse CI Player B' "$PLAYER_B_EMAIL" "$PLAYER_B_PHONE"

# Exercise the browser cookie contract itself (no Authorization header).
curl -fsS -b "$COOKIE_AUTH_JAR" -c "$COOKIE_AUTH_JAR" "$BASE_URL/auth/csrf" >/dev/null
COOKIE_CSRF="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$COOKIE_AUTH_JAR")"
[[ -n "$COOKIE_CSRF" ]] || { echo "Cookie-auth CSRF bootstrap failed" >&2; exit 1; }
COOKIE_LOGIN_STATUS="$(curl -sS -b "$COOKIE_AUTH_JAR" -c "$COOKIE_AUTH_JAR" -o /dev/null -w '%{http_code}' \
  -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $COOKIE_CSRF" \
  --data "$(jq -nc --arg email "$OWNER_A_EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
[[ "$COOKIE_LOGIN_STATUS" == "200" ]] || { echo "Cookie-auth login failed: HTTP $COOKIE_LOGIN_STATUS" >&2; exit 1; }
COOKIE_PROFILE="$(curl -fsS -b "$COOKIE_AUTH_JAR" "$BASE_URL/players/me")"
COOKIE_PLAYER_ID="$(jq -r '.id' <<<"$COOKIE_PROFILE")"
[[ -n "$COOKIE_PLAYER_ID" && "$COOKIE_PLAYER_ID" != "null" ]] || { echo "Cookie-only /players/me did not return a player" >&2; exit 1; }
NO_CSRF_STATUS="$(curl -sS -b "$COOKIE_AUTH_JAR" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/teams" \
  -H 'Content-Type: application/json' --data '{"name":"must-be-rejected","city":"CI"}')"
[[ "$NO_CSRF_STATUS" == "403" ]] || { echo "Expected cookie-auth POST without CSRF to return 403, got $NO_CSRF_STATUS" >&2; exit 1; }
COOKIE_CSRF="$(curl -fsS -b "$COOKIE_AUTH_JAR" -c "$COOKIE_AUTH_JAR" "$BASE_URL/auth/csrf" | jq -r '.token')"
LOGOUT_STATUS="$(curl -sS -b "$COOKIE_AUTH_JAR" -c "$COOKIE_AUTH_JAR" -o /dev/null -w '%{http_code}' \
  -X POST "$BASE_URL/auth/logout" -H "X-XSRF-TOKEN: $COOKIE_CSRF")"
[[ "$LOGOUT_STATUS" == "204" ]] || { echo "Cookie-auth logout failed: HTTP $LOGOUT_STATUS" >&2; exit 1; }
AFTER_LOGOUT_STATUS="$(curl -sS -b "$COOKIE_AUTH_JAR" -o /dev/null -w '%{http_code}' "$BASE_URL/auth/me")"
[[ "$AFTER_LOGOUT_STATUS" == "403" ]] || { echo "Expected unauthenticated /auth/me after logout to be rejected (403), got $AFTER_LOGOUT_STATUS" >&2; exit 1; }
echo "Verified HttpOnly cookie login, cookie-only API access, CSRF rejection, and logout"

# Login success clears the previous CSRF token; bootstrap a fresh token before every unsafe cookie-auth request.
refresh_csrf() {
  local response
  response="$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE_URL/auth/csrf")"
  CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { token=$7 } END { print token }' "$COOKIE_JAR")"
  if [[ -z "$CSRF_TOKEN" ]]; then
    echo "CSRF refresh did not persist XSRF-TOKEN. Response: $response" >&2
    exit 1
  fi
}
# CI reads the JWT only from the HttpOnly cookie jar; the login JSON no longer exposes it.
# All downstream lifecycle E2E scripts use the same cookie-backed login contract.
login_token() {
  refresh_csrf
  curl_json POST "$BASE_URL/auth/login" "$(jq -nc --arg e "$1" --arg p "$PASSWORD" '{email:$e,password:$p}')" >/dev/null
  awk '$6 == "CRICPULSE_ACCESS" { print $7 }' "$COOKIE_JAR" | tail -n 1
}
OWNER_A_TOKEN="$(login_token "$OWNER_A_EMAIL")"; OWNER_B_TOKEN="$(login_token "$OWNER_B_EMAIL")"
test -n "$OWNER_A_TOKEN" && test "$OWNER_A_TOKEN" != null; test -n "$OWNER_B_TOKEN" && test "$OWNER_B_TOKEN" != null
AUTH_A=(-H "Authorization: Bearer $OWNER_A_TOKEN"); AUTH_B=(-H "Authorization: Bearer $OWNER_B_TOKEN")

OWNER_A_PROFILE="$(curl_api "${AUTH_A[@]}" "$BASE_URL/players/me")"
PLAYER_A_PROFILE="$(curl_api -H "Authorization: Bearer $(login_token "$PLAYER_A_EMAIL")" "$BASE_URL/players/me")"
PLAYER_A2_PROFILE="$(curl_api -H "Authorization: Bearer $(login_token "$PLAYER_A2_EMAIL")" "$BASE_URL/players/me")"
PLAYER_A3_PROFILE="$(curl_api -H "Authorization: Bearer $(login_token "$PLAYER_A3_EMAIL")" "$BASE_URL/players/me")"
OWNER_B_PROFILE="$(curl_api "${AUTH_B[@]}" "$BASE_URL/players/me")"
PLAYER_B_PROFILE="$(curl_api -H "Authorization: Bearer $(login_token "$PLAYER_B_EMAIL")" "$BASE_URL/players/me")"
OWNER_A_PLAYER_ID="$(jq -r '.id' <<<"$OWNER_A_PROFILE")"; PLAYER_A_ID="$(jq -r '.id' <<<"$PLAYER_A_PROFILE")"; PLAYER_A2_ID="$(jq -r '.id' <<<"$PLAYER_A2_PROFILE")"; PLAYER_A3_ID="$(jq -r '.id' <<<"$PLAYER_A3_PROFILE")"
OWNER_B_PLAYER_ID="$(jq -r '.id' <<<"$OWNER_B_PROFILE")"; PLAYER_B_ID="$(jq -r '.id' <<<"$PLAYER_B_PROFILE")"

test -n "$OWNER_A_PLAYER_ID" && test "$OWNER_A_PLAYER_ID" != null; test -n "$PLAYER_A_ID" && test "$PLAYER_A_ID" != null; test -n "$PLAYER_A2_ID" && test "$PLAYER_A2_ID" != null; test -n "$PLAYER_A3_ID" && test "$PLAYER_A3_ID" != null
test -n "$OWNER_B_PLAYER_ID" && test "$OWNER_B_PLAYER_ID" != null; test -n "$PLAYER_B_ID" && test "$PLAYER_B_ID" != null

TEAM_A="$(curl_api -X POST "$BASE_URL/teams" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg n "CricPulse CI A ${RUN_KEY}" '{name:$n,city:"CI"}')")"; TEAM_A_ID="$(jq -r '.id' <<<"$TEAM_A")"
TEAM_B="$(curl_api -X POST "$BASE_URL/teams" "${AUTH_B[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg n "CricPulse CI B ${RUN_KEY}" '{name:$n,city:"CI"}')")"; TEAM_B_ID="$(jq -r '.id' <<<"$TEAM_B")"
test -n "$TEAM_A_ID" && test -n "$TEAM_B_ID"

# Team creation already establishes the authenticated owner as team management
# member. Only the additional player needs an explicit roster membership.
curl_api -X POST "$BASE_URL/teams/$TEAM_A_ID/members" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg p "$PLAYER_A_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null
curl_api -X POST "$BASE_URL/teams/$TEAM_A_ID/members" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg p "$PLAYER_A2_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null
curl_api -X POST "$BASE_URL/teams/$TEAM_A_ID/members" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg p "$PLAYER_A3_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null
curl_api -X POST "$BASE_URL/teams/$TEAM_B_ID/members" "${AUTH_B[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg p "$PLAYER_B_ID" '{playerId:$p,role:"PLAYER"}')" >/dev/null

TEAM_B2="$(curl_api -X POST "$BASE_URL/teams" "${AUTH_B[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg n "CricPulse CI B2 ${RUN_KEY}" '{name:$n,city:"CI"}')")"
TEAM_B2_ID="$(jq -r '.id' <<<"$TEAM_B2")"
test -n "$TEAM_B2_ID" && test "$TEAM_B2_ID" != null

echo "Created teams and populated disposable rosters"

MATCH="$(curl_api -X POST "$BASE_URL/matches" "${AUTH_A[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg n "CricPulse CI Lifecycle ${RUN_KEY}" --arg a "$TEAM_A_ID" --arg b "$TEAM_B_ID" '{name:$n,teamAId:$a,teamBId:$b,format:"CUSTOM",totalOvers:4}')")"; MATCH_ID="$(jq -r '.id' <<<"$MATCH")"
test -n "$MATCH_ID" && test "$MATCH_ID" != null
echo "Created fixture match=$MATCH_ID teamA=$TEAM_A_ID teamB=$TEAM_B_ID"

PRIVATE_MATCH="$(curl_api -X POST "$BASE_URL/matches" "${AUTH_B[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg n "CricPulse CI Private ${RUN_KEY}" --arg a "$TEAM_B_ID" --arg b "$TEAM_B2_ID" '{name:$n,teamAId:$a,teamBId:$b,format:"T20",totalOvers:20}')")"
PRIVATE_MATCH_ID="$(jq -r '.id' <<<"$PRIVATE_MATCH")"
test -n "$PRIVATE_MATCH_ID" && test "$PRIVATE_MATCH_ID" != null
echo "Created owner-B-only match=$PRIVATE_MATCH_ID"

select_xi() { local auth_name="$1" team_id="$2" player_id="$3"; local -n auth_ref="$auth_name"; curl_api -X POST "$BASE_URL/matches/$MATCH_ID/playing-xi" "${auth_ref[@]}" -H 'Content-Type: application/json' --data "$(jq -nc --arg t "$team_id" --arg p "$player_id" '{teamId:$t,playerId:$p,captain:false,viceCaptain:false,wicketKeeper:false}')" >/dev/null; }
select_xi AUTH_A "$TEAM_A_ID" "$OWNER_A_PLAYER_ID"; select_xi AUTH_A "$TEAM_A_ID" "$PLAYER_A_ID"; select_xi AUTH_B "$TEAM_B_ID" "$OWNER_B_PLAYER_ID"; select_xi AUTH_B "$TEAM_B_ID" "$PLAYER_B_ID"
echo "Selected Playing XI for both teams"

cat >> "$GITHUB_ENV" <<EOF
E2E_EMAIL=$OWNER_A_EMAIL
E2E_OWNER_B_EMAIL=$OWNER_B_EMAIL
E2E_PASSWORD=$PASSWORD
E2E_TEAM_A_ID=$TEAM_A_ID
E2E_TEAM_B_ID=$TEAM_B_ID
E2E_OWNER_PLAYER_ID=$OWNER_A_PLAYER_ID
E2E_PLAYER_ID=$PLAYER_A_ID
E2E_A_NEW_BATTER_ID=$PLAYER_A2_ID
E2E_A_FINAL_BATTER_ID=$PLAYER_A3_ID
E2E_B_OWNER_PLAYER_ID=$OWNER_B_PLAYER_ID
E2E_B_PLAYER_ID=$PLAYER_B_ID
E2E_MATCH_ID=$MATCH_ID
E2E_PRIVATE_MATCH_ID=$PRIVATE_MATCH_ID
E2E_PRIVATE_TEAM_B2_ID=$TEAM_B2_ID
EOF
