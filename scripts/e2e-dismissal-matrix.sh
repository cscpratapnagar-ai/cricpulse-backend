#!/usr/bin/env bash
set -euo pipefail

# Task #170: four real dismissal API scenarios over two isolated PostgreSQL matches.
# Two matches allow four wickets with the four batting-team CI fixtures while
# ensuring no dismissed player is reused as a replacement.
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
command -v psql >/dev/null

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
  local token="$1" method="$2" url="$3" body="${4:-}" extra_header="${5:-}" status
  local -a args=(-sS -o "$tmp/response.json" -w '%{http_code}' -X "$method" "$url"
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json')
  [[ -n "$extra_header" ]] && args+=(-H "$extra_header")
  [[ -n "$body" ]] && args+=(--data "$body")
  status="$(curl "${args[@]}")"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "FAIL: $method $url returned HTTP $status" >&2
    cat "$tmp/response.json" >&2
    exit 1
  fi
  cat "$tmp/response.json"
}

assert_eq() {
  local label="$1" actual="$2" expected="$3"
  [[ "$actual" == "$expected" ]] || {
    echo "FAIL: $label expected=$expected actual=$actual" >&2
    exit 1
  }
  echo "PASS: $label = $actual"
}

token_a="$(login_token "$EMAIL" "$tmp/owner-a.cookies")"
token_b="$(login_token "$OWNER_B_EMAIL" "$tmp/owner-b.cookies")"

run_matrix_match() {
  local match_label="$1" first_type="$2" second_type="$3" first_fielder="$4" second_fielder="$5"
  local match match_id innings innings_id opening lease_token
  local striker non newbat finalbat bowler delivery score public_score broadcast scorecard
  local db_rows expected_rows

  echo "== $match_label: $first_type then $second_type =="
  match="$(request "$token_a" POST "$BASE_URL/matches" "$(jq -nc --arg a "$TEAM_A_ID" --arg b "$TEAM_B_ID" --arg name "CricPulse Task 170 $match_label $(date -u +%s%N)" '{name:$name,teamAId:$a,teamBId:$b,format:"CUSTOM",totalOvers:4}')")"
  match_id="$(jq -r '.id // empty' <<<"$match")"
  [[ -n "$match_id" && "$match_id" != null ]]

  local player
  for player in "$STRIKER_ID" "$NON_STRIKER_ID" "$NEW_BATTER_ID" "$FINAL_BATTER_ID"; do
    request "$token_a" POST "$BASE_URL/matches/$match_id/playing-xi" \
      "$(jq -nc --arg team "$TEAM_A_ID" --arg player "$player" '{teamId:$team,playerId:$player,captain:false,viceCaptain:false,wicketKeeper:false}')" >/dev/null
  done
  # The bowling-side player is also the designated keeper for the Stumped scenario.
  request "$token_b" POST "$BASE_URL/matches/$match_id/playing-xi" \
    "$(jq -nc --arg team "$TEAM_B_ID" --arg player "$BOWLER_ID" '{teamId:$team,playerId:$player,captain:false,viceCaptain:false,wicketKeeper:true}')" >/dev/null

  request "$token_a" POST "$BASE_URL/matches/$match_id/toss" \
    "$(jq -nc --arg match "$match_id" --arg team "$TEAM_A_ID" '{matchId:$match,winnerTeamId:$team,decision:"BAT"}')" >/dev/null
  innings="$(request "$token_a" POST "$BASE_URL/scoring/innings" \
    "$(jq -nc --arg match "$match_id" --arg team "$TEAM_A_ID" --arg striker "$STRIKER_ID" --arg non "$NON_STRIKER_ID" --arg bowler "$BOWLER_ID" '{matchId:$match,inningsNumber:1,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')")"
  innings_id="$(jq -r '.id // .inningsId // empty' <<<"$innings")"
  [[ -n "$innings_id" && "$innings_id" != null ]]
  opening="$(jq -nc --arg striker "$STRIKER_ID" --arg non "$NON_STRIKER_ID" --arg bowler "$BOWLER_ID" '{strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler}')"
  request "$token_a" POST "$BASE_URL/scoring/innings/$innings_id/opening" "$opening" >/dev/null
  lease_token="$(cat /proc/sys/kernel/random/uuid)"
  request "$token_a" POST "$BASE_URL/scoring/innings/$innings_id/lease" \
    "$(jq -nc --arg token "$lease_token" '{leaseToken:$token}')" >/dev/null

  local current_striker="$STRIKER_ID" current_non="$NON_STRIKER_ID"
  local replacement="$NEW_BATTER_ID" wicket_index=1 wicket_type fielder dismissed next_batter

  local ws_url="${BASE_URL%/api}/ws"
  ws_url="${ws_url/http:\/\//ws:\/\/}"
  ws_url="${ws_url/https:\/\//wss:\/\/}"
  export WS_URL="$ws_url" BASE_URL INNINGS_ID="$innings_id"
  export WS_READY_FILE="$tmp/ws-ready" WS_DONE_FILE="$tmp/ws-done" WS_OUTPUT_FILE="$tmp/ws-result.json"
  export EXPECTED_DISMISSALS
  EXPECTED_DISMISSALS="$(jq -nc --arg a "$first_type" --arg b "$second_type" --arg first "$STRIKER_ID" --arg second "$NEW_BATTER_ID" '[{wicketType:$a,dismissedPlayerId:$first},{wicketType:$b,dismissedPlayerId:$second}]')"
  node scripts/e2e-dismissal-stomp-monitor.mjs >"$tmp/ws.log" 2>&1 &
  ws_pid=$!
  for attempt in {1..150}; do
    [[ -f "$WS_READY_FILE" ]] && break
    if ! kill -0 "$ws_pid" 2>/dev/null; then cat "$tmp/ws.log" >&2; exit 1; fi
    sleep 0.1
  done
  [[ -f "$WS_READY_FILE" ]] || { echo "STOMP dismissal subscriber did not become ready" >&2; cat "$tmp/ws.log" >&2; exit 1; }

  for wicket_type in "$first_type" "$second_type"; do
    if [[ "$wicket_index" -eq 1 ]]; then
      dismissed="$current_striker"
      next_batter="$NEW_BATTER_ID"
      fielder="$first_fielder"
    else
      dismissed="$NEW_BATTER_ID"
      next_batter="$FINAL_BATTER_ID"
      fielder="$second_fielder"
    fi
    delivery="$(jq -nc --arg innings "$innings_id" --arg striker "$current_striker" \
      --arg non "$current_non" --arg bowler "$BOWLER_ID" --arg wicket "$wicket_type" \
      --arg dismissed "$dismissed" --arg newbat "$next_batter" --arg fielder "$fielder" \
      --argjson ball "$wicket_index" \
      '{inningsId:$innings,overNumber:0,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:0,extraRuns:0,extraType:null,wicketType:$wicket,dismissedPlayerId:$dismissed,newBatterId:$newbat,wicketFielderId:(if $fielder=="" then null else $fielder end)}')"
    request "$token_a" POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "$delivery" "X-Scorer-Lease: $lease_token" >/dev/null
    current_striker="$next_batter"
    wicket_index=$((wicket_index+1))
  done

  for attempt in {1..450}; do [[ -f "$WS_DONE_FILE" ]] && break; sleep 0.1; done
  if [[ ! -f "$WS_DONE_FILE" ]]; then echo "STOMP dismissal verification timed out" >&2; cat "$tmp/ws.log" >&2; exit 1; fi
  wait "$ws_pid"
  ws_pid=""
  cat "$tmp/ws.log"
  jq -e '.ok == true' "$WS_OUTPUT_FILE" >/dev/null

  score="$(request "$token_a" GET "$BASE_URL/scoring/innings/$innings_id")"
  public_score="$(curl -fsS "$BASE_URL/public/innings/$innings_id")"
  broadcast="$(curl -fsS "$BASE_URL/public/matches/$match_id/broadcast-state")"
  scorecard="$(request "$token_a" GET "$BASE_URL/matches/$match_id/scorecard")"

  assert_eq "$match_label wicket count" "$(jq -r '.wickets' <<<"$score")" "2"
  assert_eq "$match_label legal balls" "$(jq -r '.legalBalls' <<<"$score")" "2"
  assert_eq "$match_label final striker replacement" "$(jq -r '.strikerId' <<<"$score")" "$FINAL_BATTER_ID"
  local expected_bowler_wickets=2
  [[ "$first_type" == "RUN_OUT" ]] && expected_bowler_wickets=1
  [[ "$second_type" == "RUN_OUT" ]] && expected_bowler_wickets=$((expected_bowler_wickets-1))
  assert_eq "$match_label bowler wicket credits" "$(jq -r --arg id "$BOWLER_ID" '.bowlers[] | select(.playerId==$id) | .wickets' <<<"$score")" "$expected_bowler_wickets"
  assert_eq "$match_label bowler economy" "$(jq -r --arg id "$BOWLER_ID" '.bowlers[] | select(.playerId==$id) | .economy' <<<"$score")" "0.00"
  assert_eq "$match_label fall of wickets" "$(jq -r '.fallOfWickets | length' <<<"$score")" "2"
  assert_eq "$match_label second partnership reset" "$(jq -r '.partnership.runs' <<<"$score")/$(jq -r '.partnership.balls' <<<"$score")" "0/0"
  assert_eq "$match_label public REST parity" "$(jq -r '"\(.runs)/\(.wickets)/\(.legalBalls)/\(.eventVersion)"' <<<"$public_score")" "$(jq -r '"\(.runs)/\(.wickets)/\(.legalBalls)/\(.eventVersion)"' <<<"$score")"
  assert_eq "$match_label broadcast parity" "$(jq -r --arg id "$innings_id" '[.innings[] | select(.score.inningsId==$id) | .score | "\(.runs)/\(.wickets)/\(.legalBalls)/\(.eventVersion)"] | first' <<<"$broadcast")" "$(jq -r '"\(.runs)/\(.wickets)/\(.legalBalls)/\(.eventVersion)"' <<<"$score")"

  assert_eq "$match_label first dismissal type" "$(jq -r '.batters[] | select(.playerId=="'"$STRIKER_ID"'") | .dismissalType' <<<"$score")" "$first_type"
  assert_eq "$match_label second dismissal type" "$(jq -r '.batters[] | select(.playerId=="'"$NEW_BATTER_ID"'") | .dismissalType' <<<"$score")" "$second_type"
  assert_eq "$match_label scorecard dismissal rows" "$(jq -r --arg a "$first_type" --arg b "$second_type" '[.[] | select(.inningsNumber==1) | .batting[]? | select(.dismissal==$a or .dismissal==$b)] | length' <<<"$scorecard")" "2"
  db_rows="$(psql -v ON_ERROR_STOP=1 -Atc "SELECT wicket_type || ':' || COALESCE(wicket_fielder_id::text,'-') FROM deliveries WHERE innings_id='$innings_id' AND wicket_type IS NOT NULL ORDER BY sequence_number")"
  expected_rows="$(printf '%s:%s\n%s:%s' "$first_type" "${first_fielder:--}" "$second_type" "${second_fielder:--}")"
  assert_eq "$match_label persisted wicket/fielder attribution" "$db_rows" "$expected_rows"
  assert_eq "$match_label persisted FOW rows" "$(psql -v ON_ERROR_STOP=1 -Atc "SELECT COUNT(*) FROM fall_of_wickets WHERE innings_id='$innings_id'")" "2"
  echo "PASS: $match_label API, PostgreSQL, scorecard, bowler figures, fielder and public projection certification"
}

# Each match is observed live over anonymous public STOMP before the first wicket.
# The third scenario explicitly dismisses the striker by Run Out; Task #169 covers
# the non-striker Run Out and crossing/replacement path.
run_matrix_match "Caught-LBW" "CAUGHT" "LBW" "$BOWLER_ID" ""
run_matrix_match "Stumped-HitWicket" "STUMPED" "HIT_WICKET" "$BOWLER_ID" ""
run_matrix_match "Striker-RunOut-Caught" "RUN_OUT" "CAUGHT" "" "$BOWLER_ID"

echo "PASS: Task #171 live STOMP dismissal matrix, striker Run Out and reconnect snapshot recovery"
