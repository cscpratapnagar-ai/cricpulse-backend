#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:?EMAIL is required}"
OWNER_B_EMAIL="${OWNER_B_EMAIL:?OWNER_B_EMAIL is required}"
PASSWORD="${PASSWORD:?PASSWORD is required}"
TEAM_A_ID="${TEAM_A_ID:?TEAM_A_ID is required}"
TEAM_B_ID="${TEAM_B_ID:?TEAM_B_ID is required}"
STRIKER_ID="${STRIKER_ID:?STRIKER_ID is required}"
NON_STRIKER_ID="${NON_STRIKER_ID:?NON_STRIKER_ID is required}"
NEW_BATTER_ID="${NEW_BATTER_ID:?NEW_BATTER_ID is required}"
FINAL_BATTER_ID="${FINAL_BATTER_ID:?FINAL_BATTER_ID is required}"
BOWLER_ID="${BOWLER_ID:?BOWLER_ID is required}"

command -v curl >/dev/null
command -v jq >/dev/null
tmp="$(mktemp -d)"
ws_pid=""
cleanup() {
  if [[ -n "$ws_pid" ]]; then kill "$ws_pid" 2>/dev/null || true; fi
  rm -rf "$tmp"
}
trap cleanup EXIT

login_token() {
  local email="$1" jar="$2" csrf token
  curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" >/dev/null
  csrf="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$jar")"
  [[ -n "$csrf" ]] || { echo "CSRF cookie missing for $email" >&2; return 1; }
  curl -fsS -b "$jar" -c "$jar" -X POST "$BASE_URL/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
    --data "$(jq -nc --arg email "$email" --arg password "$PASSWORD" '{email:$email,password:$password}')" >/dev/null
  token="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$jar")"
  [[ -n "$token" ]] || { echo "Access cookie missing for $email" >&2; return 1; }
  printf '%s' "$token"
}

request() {
  local token="$1" method="$2" url="$3" body="${4:-}" status
  local -a args=(-sS -o "$tmp/response.json" -w '%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $token" -H 'Content-Type: application/json')
  if [[ -n "$body" ]]; then args+=(--data "$body"); fi
  status="$(curl "${args[@]}")"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "FAIL: $method $url returned HTTP $status" >&2
    cat "$tmp/response.json" >&2
    exit 1
  fi
  cat "$tmp/response.json"
}

echo "[1/7] Authenticate two real match owners"
token_a="$(login_token "$EMAIL" "$tmp/owner-a.cookies")"
token_b="$(login_token "$OWNER_B_EMAIL" "$tmp/owner-b.cookies")"

echo "[2/7] Create isolated real PostgreSQL-backed match and three-batter XI"
match="$(request "$token_a" POST "$BASE_URL/matches" "$(jq -nc --arg a "$TEAM_A_ID" --arg b "$TEAM_B_ID" --arg name "CricPulse real wicket E2E $(date -u +%s)" '{name:$name,teamAId:$a,teamBId:$b,format:"CUSTOM",totalOvers:4}')")"
match_id="$(jq -r '.id // empty' <<<"$match")"
[[ -n "$match_id" && "$match_id" != null ]]
select_xi() {
  local token="$1" team="$2" player="$3"
  request "$token" POST "$BASE_URL/matches/$match_id/playing-xi" "$(jq -nc --arg team "$team" --arg player "$player" '{teamId:$team,playerId:$player,captain:false,viceCaptain:false,wicketKeeper:false}')" >/dev/null
}
select_xi "$token_a" "$TEAM_A_ID" "$STRIKER_ID"
select_xi "$token_a" "$TEAM_A_ID" "$NON_STRIKER_ID"
select_xi "$token_a" "$TEAM_A_ID" "$NEW_BATTER_ID"
select_xi "$token_a" "$TEAM_A_ID" "$FINAL_BATTER_ID"
select_xi "$token_b" "$TEAM_B_ID" "$BOWLER_ID"
toss="$(jq -nc --arg match "$match_id" --arg team "$TEAM_A_ID" '{matchId:$match,winnerTeamId:$team,decision:"BAT"}')"
request "$token_a" POST "$BASE_URL/matches/$match_id/toss" "$toss" >/dev/null

echo "[3/7] Start innings and acquire scorer lease"
innings_body="$(jq -nc --arg match "$match_id" --arg team "$TEAM_A_ID" --arg striker "$STRIKER_ID" --arg non "$NON_STRIKER_ID" --arg bowler "$BOWLER_ID" '{matchId:$match,inningsNumber:1,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
innings="$(request "$token_a" POST "$BASE_URL/scoring/innings" "$innings_body")"
innings_id="$(jq -r '.id // .inningsId // empty' <<<"$innings")"
[[ -n "$innings_id" && "$innings_id" != null ]]
opening="$(jq -nc --arg striker "$STRIKER_ID" --arg non "$NON_STRIKER_ID" --arg bowler "$BOWLER_ID" '{strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler}')"
request "$token_a" POST "$BASE_URL/scoring/innings/$innings_id/opening" "$opening" >/dev/null
lease_token="$(cat /proc/sys/kernel/random/uuid)"
request "$token_a" POST "$BASE_URL/scoring/innings/$innings_id/lease" "$(jq -nc --arg token "$lease_token" '{leaseToken:$token}')" >/dev/null

echo "[4/7] Subscribe anonymous public STOMP viewer before the wicket delivery"
export WS_URL="${BASE_URL%/api}/ws"
export WS_URL="${WS_URL/http:\/\//ws:\/\/}"
export WS_URL="${WS_URL/https:\/\//wss:\/\/}"
export INNINGS_ID="$innings_id"
export BASE_URL
export NEW_BATTER_ID="$NEW_BATTER_ID"
export FINAL_BATTER_ID="$FINAL_BATTER_ID"
export STRIKER_ID="$STRIKER_ID"
export DISMISSED_PLAYER_ID="$NON_STRIKER_ID"
export FINAL_DISMISSED_PLAYER_ID="$NEW_BATTER_ID"
export WS_READY_FILE="$tmp/ws-ready"
export WS_DONE_FILE="$tmp/ws-done"
export WS_OUTPUT_FILE="$tmp/ws-result.json"
node scripts/e2e-wicket-stomp-reconnect.mjs >"$tmp/ws.log" 2>&1 &
ws_pid=$!
for attempt in {1..150}; do
  [[ -f "$WS_READY_FILE" ]] && break
  if ! kill -0 "$ws_pid" 2>/dev/null; then cat "$tmp/ws.log" >&2; exit 1; fi
  sleep 0.1
done
[[ -f "$WS_READY_FILE" ]] || { echo "STOMP public subscriber did not become ready" >&2; cat "$tmp/ws.log" >&2; exit 1; }

echo "[5/7] Record RUN_OUT of the non-striker with one completed run, then a Bowled dismissal"
delivery="$(jq -nc --arg innings "$innings_id" --arg striker "$STRIKER_ID" --arg non "$NON_STRIKER_ID" --arg bowler "$BOWLER_ID" --arg dismissed "$NON_STRIKER_ID" --arg newbat "$NEW_BATTER_ID" '{inningsId:$innings,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,overNumber:0,ballNumber:1,batRuns:1,extraRuns:0,extraType:null,wicketType:"RUN_OUT",dismissedPlayerId:$dismissed,newBatterId:$newbat}')"
request "$token_a" POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "$delivery" >/dev/null
bowled="$(jq -nc --arg innings "$innings_id" --arg striker "$NEW_BATTER_ID" --arg non "$STRIKER_ID" --arg bowler "$BOWLER_ID" --arg dismissed "$NEW_BATTER_ID" --arg newbat "$FINAL_BATTER_ID" '{inningsId:$innings,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,overNumber:0,ballNumber:2,batRuns:0,extraRuns:0,extraType:null,wicketType:"BOWLED",dismissedPlayerId:$dismissed,newBatterId:$newbat}')"
request "$token_a" POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "$bowled" >/dev/null

echo "[6/7] Verify authoritative PostgreSQL projection, scorecard, partnerships and public Match Centre"
score="$(request "$token_a" GET "$BASE_URL/scoring/innings/$innings_id")"
public_score="$(curl -fsS "$BASE_URL/public/innings/$innings_id")"
broadcast="$(curl -fsS "$BASE_URL/public/matches/$match_id/broadcast-state")"
partnerships="$(request "$token_a" GET "$BASE_URL/matches/$match_id/partnerships")"
scorecard="$(request "$token_a" GET "$BASE_URL/matches/$match_id/scorecard")"

assert_eq() {
  local label="$1" actual="$2" expected="$3"
  [[ "$actual" == "$expected" ]] || { echo "FAIL: $label expected=$expected actual=$actual" >&2; exit 1; }
  echo "PASS: $label = $actual"
}
assert_eq "team score" "$(jq -r '.runs' <<<"$score")" "1"
assert_eq "wickets" "$(jq -r '.wickets' <<<"$score")" "2"
assert_eq "legal balls" "$(jq -r '.legalBalls' <<<"$score")" "2"
assert_eq "replacement batter after second wicket" "$(jq -r '.strikerId' <<<"$score")" "$FINAL_BATTER_ID"
assert_eq "original striker at non-striker end" "$(jq -r '.nonStrikerId' <<<"$score")" "$STRIKER_ID"
assert_eq "dismissed non-striker marked RUN_OUT" "$(jq -r --arg id "$NON_STRIKER_ID" '[.batters[] | select(.playerId==$id and .out==true and .dismissalType=="RUN_OUT")] | length' <<<"$score")" "1"
assert_eq "striker marked BOWLED" "$(jq -r --arg id "$NEW_BATTER_ID" '[.batters[] | select(.playerId==$id and .out==true and .dismissalType=="BOWLED")] | length' <<<"$score")" "1"
assert_eq "first fall of wicket dismissed non-striker" "$(jq -r '.fallOfWickets[0].playerId' <<<"$score")" "$NON_STRIKER_ID"
assert_eq "second fall of wicket dismissed batter" "$(jq -r '.fallOfWickets[1].playerId' <<<"$score")" "$NEW_BATTER_ID"
assert_eq "first fall of wicket score" "$(jq -r '.fallOfWickets[0].runs' <<<"$score")" "1"
assert_eq "second fall of wicket score" "$(jq -r '.fallOfWickets[1].runs' <<<"$score")" "1"
assert_eq "second fall of wicket over.ball" "$(jq -r '"\(.fallOfWickets[1].overNumber).\(.fallOfWickets[1].ballNumber)"' <<<"$score")" "0.2"
assert_eq "bowler wicket credit (Run Out excluded, Bowled credited)" "$(jq -r --arg id "$BOWLER_ID" '.bowlers[] | select(.playerId==$id) | .wickets' <<<"$score")" "1"
assert_eq "bowler conceded runs" "$(jq -r --arg id "$BOWLER_ID" '.bowlers[] | select(.playerId==$id) | .runsConceded' <<<"$score")" "1"
assert_eq "bowler legal balls" "$(jq -r --arg id "$BOWLER_ID" '.bowlers[] | select(.playerId==$id) | .legalBalls' <<<"$score")" "2"
assert_eq "bowler economy" "$(jq -r --arg id "$BOWLER_ID" '.bowlers[] | select(.playerId==$id) | .economy' <<<"$score")" "3.00"
assert_eq "current partnership reset after second wicket" "$(jq -r '.partnership.runs' <<<"$score")/$(jq -r '.partnership.balls' <<<"$score")" "0/0"
assert_eq "first partnership includes Run Out delivery" "$(jq -r '[.[] | select(.inningsNumber==1 and .partnershipNumber==1) | "\(.runs)/\(.balls)/\(.current)"] | first' <<<"$partnerships")" "1/1/false"
assert_eq "second partnership includes Bowled delivery" "$(jq -r '[.[] | select(.inningsNumber==1 and .partnershipNumber==2) | "\(.runs)/\(.balls)/\(.current)"] | first' <<<"$partnerships")" "0/1/false"
partnership_rows="$(psql -v ON_ERROR_STOP=1 -Atc "SELECT wicket_number || ':' || runs || '/' || balls || ':' || is_current FROM partnerships WHERE innings_id = '$innings_id' ORDER BY wicket_number")"
assert_eq "persisted partnership split in PostgreSQL" "$partnership_rows" "$(printf '0:1/1:false\n1:0/1:false\n2:0/0:true')"
assert_eq "public REST score parity" "$(jq -r '"\(.runs)/\(.wickets)/\(.legalBalls)/\(.eventVersion)"' <<<"$public_score")" "$(jq -r '"\(.runs)/\(.wickets)/\(.legalBalls)/\(.eventVersion)"' <<<"$score")"
assert_eq "broadcast Match Centre score parity" "$(jq -r --arg id "$innings_id" '[.innings[] | select(.score.inningsId==$id) | .score | "\(.runs)/\(.wickets)/\(.legalBalls)/\(.eventVersion)"] | first' <<<"$broadcast")" "$(jq -r '"\(.runs)/\(.wickets)/\(.legalBalls)/\(.eventVersion)"' <<<"$score")"
assert_eq "scorecard innings contains RUN_OUT" "$(jq -r --arg id "$NON_STRIKER_ID" '[.[] | select(.inningsNumber==1) | .batting[]? | select(.playerId==$id and .dismissal=="RUN_OUT")] | length' <<<"$scorecard")" "1"
assert_eq "scorecard innings contains BOWLED" "$(jq -r --arg id "$NEW_BATTER_ID" '[.[] | select(.inningsNumber==1) | .batting[]? | select(.playerId==$id and .dismissal=="BOWLED")] | length' <<<"$scorecard")" "1"
assert_eq "scorecard bowler does not get Run Out credit" "$(jq -r --arg id "$BOWLER_ID" '[.[] | select(.inningsNumber==1) | .bowling[]? | select(.playerId==$id) | .wickets] | first' <<<"$scorecard")" "0"

echo "[7/7] Verify STOMP delivery and reconnect snapshot recovery"
for attempt in {1..200}; do [[ -f "$WS_DONE_FILE" ]] && break; sleep 0.1; done
if [[ ! -f "$WS_DONE_FILE" ]]; then echo "STOMP wicket/reconnect verification timed out" >&2; cat "$tmp/ws.log" >&2; exit 1; fi
wait "$ws_pid"
ws_pid=""
cat "$tmp/ws.log"
jq -e '.ok == true' "$WS_OUTPUT_FILE" >/dev/null
echo "PASS: real RUN_OUT and BOWLED integration, PostgreSQL projections, bowler credit/economy, scorecard, partnerships, public Match Centre and STOMP reconnect certification"
