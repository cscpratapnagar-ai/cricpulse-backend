#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"; OWNER_B_EMAIL="${OWNER_B_EMAIL:-}"; PASSWORD="${PASSWORD:-}"; MATCH_ID="${MATCH_ID:-}"
TEAM_A_ID="${TEAM_A_ID:-}"; TEAM_B_ID="${TEAM_B_ID:-}"
A_STRIKER_ID="${A_STRIKER_ID:-}"; A_NON_STRIKER_ID="${A_NON_STRIKER_ID:-}"
B_BOWLER_ID="${B_BOWLER_ID:-}"; B_STRIKER_ID="${B_STRIKER_ID:-}"
B_NON_STRIKER_ID="${B_NON_STRIKER_ID:-}"; A_BOWLER_ID="${A_BOWLER_ID:-}"
TOURNAMENT_ID="${TOURNAMENT_ID:-}"
SCORER_LEASE_TOKEN=""
MATCH_NAME="${MATCH_NAME:-CricPulse Lifecycle E2E $(date -u +%Y%m%d-%H%M%S)}"
required=(EMAIL OWNER_B_EMAIL PASSWORD TEAM_A_ID TEAM_B_ID A_STRIKER_ID A_NON_STRIKER_ID B_BOWLER_ID B_STRIKER_ID B_NON_STRIKER_ID A_BOWLER_ID)
for name in "${required[@]}"; do [[ -n "${!name}" ]] || { echo "ERROR: $name is required" >&2; exit 1; }; done
command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }
TMP_DIR="$(mktemp -d)"; trap 'rm -rf "$TMP_DIR"' EXIT
# Keep failed API responses for diagnosis. Never replay a failed write request:
# the server may have committed a POST even if the client did not receive its response.
LAST_RESPONSE_FILE="$TMP_DIR/last-response"
LAST_STATUS_FILE="$TMP_DIR/last-status"
request() {
  local method="$1" url="$2" body="${3:-}" status curl_status=0
  local -a headers=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
  if [[ -n "$SCORER_LEASE_TOKEN" ]]; then headers+=(-H "X-Scorer-Lease: $SCORER_LEASE_TOKEN"); fi
  if [[ -n "$body" ]]; then
    status="$(curl -sS -o "$LAST_RESPONSE_FILE" -w '%{http_code}' -X "$method" "$url" "${headers[@]}" -d "$body")" || curl_status=$?
  else
    status="$(curl -sS -o "$LAST_RESPONSE_FILE" -w '%{http_code}' -X "$method" "$url" "${headers[@]}")" || curl_status=$?
  fi
  printf '%s' "${status:-000}" > "$LAST_STATUS_FILE"
  if [[ "$curl_status" -ne 0 || ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "HTTP request failed: method=$method url=$url status=${status:-000} curl_exit=$curl_status" >&2
    if [[ -s "$LAST_RESPONSE_FILE" ]]; then cat "$LAST_RESPONSE_FILE" >&2; echo >&2; fi
    return 22
  fi
  cat "$LAST_RESPONSE_FILE"
}
request_status() { local method="$1" url="$2" body="${3:-}"; if [[ -n "$body" ]]; then curl -sS -o "$TMP_DIR/rejection-body" -w '%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body"; else curl -sS -o "$TMP_DIR/rejection-body" -w '%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $TOKEN"; fi; }
expect_rejection() { local label="$1" method="$2" url="$3" body="${4:-}" status; status="$(request_status "$method" "$url" "$body")"; if [[ "$status" -lt 400 || "$status" -ge 500 ]]; then echo "FAIL: $label expected 4xx, received HTTP $status" >&2; cat "$TMP_DIR/rejection-body" >&2 || true; exit 1; fi; echo "PASS: $label (HTTP $status)"; }

acquire_scorer_lease() {
  local innings_id="$1" response
  SCORER_LEASE_TOKEN="$(cat /proc/sys/kernel/random/uuid)"
  response="$(curl -fsS -X POST "$BASE_URL/scoring/innings/$innings_id/lease" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg token "$SCORER_LEASE_TOKEN" '{leaseToken:$token}')")"
  [[ -n "$response" ]] || { echo "ERROR: scorer lease response is empty" >&2; exit 1; }
}
renew_scorer_lease() {
  local innings_id="$1"
  curl -fsS -X PUT "$BASE_URL/scoring/innings/$innings_id/lease" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg token "$SCORER_LEASE_TOKEN" '{leaseToken:$token}')" >/dev/null
}
fail_with_body() { local label="$1"; echo "ERROR: $label" >&2; if [[ -f "$LAST_STATUS_FILE" ]]; then echo "Last HTTP status: $(cat "$LAST_STATUS_FILE")" >&2; fi; if [[ -s "$LAST_RESPONSE_FILE" ]]; then echo "Last response body:" >&2; cat "$LAST_RESPONSE_FILE" >&2; echo >&2; fi; exit 1; }

echo "[1/10] Login"
# Cookie-auth login requires a CSRF token; access JWT is returned only as HttpOnly cookie.
AUTH_COOKIE_JAR="$TMP_DIR/auth.cookies"
curl -fsS -b "$AUTH_COOKIE_JAR" -c "$AUTH_COOKIE_JAR" "$BASE_URL/auth/csrf" >/dev/null
CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$AUTH_COOKIE_JAR")"
[[ -n "$CSRF_TOKEN" ]] || { echo "ERROR: CSRF cookie missing after bootstrap" >&2; exit 1; }
LOGIN_STATUS="$(curl -sS -b "$AUTH_COOKIE_JAR" -c "$AUTH_COOKIE_JAR" -o "$TMP_DIR/login-response.json" -w '%{http_code}' \
  -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
  -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
if [[ "$LOGIN_STATUS" != "200" ]]; then
  echo "ERROR: login failed (HTTP $LOGIN_STATUS)" >&2
  cat "$TMP_DIR/login-response.json" >&2
  exit 1
fi
TOKEN="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$AUTH_COOKIE_JAR")"
[[ -n "$TOKEN" && "$TOKEN" != null ]] || { echo "ERROR: HttpOnly access cookie missing after login" >&2; exit 1; }

echo "[2/10] Prepare disposable match"
if [[ -z "$MATCH_ID" ]]; then MATCH_BODY="$(jq -nc --arg name "$MATCH_NAME" --arg teamA "$TEAM_A_ID" --arg teamB "$TEAM_B_ID" '{name:$name,teamAId:$teamA,teamBId:$teamB,format:"CUSTOM",totalOvers:4}')"; CREATED_MATCH="$(request POST "$BASE_URL/matches" "$MATCH_BODY")" || fail_with_body "create disposable match failed" POST "$BASE_URL/matches" "$MATCH_BODY"; MATCH_ID="$(jq -r '.id // empty' <<<"$CREATED_MATCH")"; [[ -n "$MATCH_ID" ]] || { echo "ERROR: created match ID missing" >&2; exit 1; }; fi
MATCH="$(request GET "$BASE_URL/matches/$MATCH_ID")" || fail_with_body "GET match failed" GET "$BASE_URL/matches/$MATCH_ID"; STATUS="$(jq -r '.status // empty' <<<"$MATCH")"; [[ "$STATUS" != "COMPLETED" ]] || { echo "ERROR: match is already COMPLETED" >&2; exit 1; }
TEAM_A_FROM_MATCH="$(jq -r '.teamAId // .team_a_id // empty' <<<"$MATCH")"; TEAM_B_FROM_MATCH="$(jq -r '.teamBId // .team_b_id // empty' <<<"$MATCH")"; [[ -z "$TEAM_A_FROM_MATCH" || "$TEAM_A_FROM_MATCH" == "$TEAM_A_ID" ]] || { echo "ERROR: Team A mismatch" >&2; exit 1; }; [[ -z "$TEAM_B_FROM_MATCH" || "$TEAM_B_FROM_MATCH" == "$TEAM_B_ID" ]] || { echo "ERROR: Team B mismatch" >&2; exit 1; }

echo "[3/10] Ensure selected Playing XI for the disposable match"
OWNER_B_COOKIE_JAR="$TMP_DIR/owner-b.cookies"
curl -fsS -b "$OWNER_B_COOKIE_JAR" -c "$OWNER_B_COOKIE_JAR" "$BASE_URL/auth/csrf" >/dev/null
OWNER_B_CSRF="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$OWNER_B_COOKIE_JAR")"
[[ -n "$OWNER_B_CSRF" ]] || { echo "ERROR: owner B CSRF cookie missing" >&2; exit 1; }
OWNER_B_LOGIN_STATUS="$(curl -sS -b "$OWNER_B_COOKIE_JAR" -c "$OWNER_B_COOKIE_JAR" -o "$TMP_DIR/owner-b-login.json" -w '%{http_code}' -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $OWNER_B_CSRF" -d "$(jq -nc --arg email "$OWNER_B_EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
[[ "$OWNER_B_LOGIN_STATUS" == "200" ]] || { echo "ERROR: owner B login failed HTTP $OWNER_B_LOGIN_STATUS" >&2; cat "$TMP_DIR/owner-b-login.json" >&2; exit 1; }
TOKEN_B="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$OWNER_B_COOKIE_JAR")"
[[ -n "$TOKEN_B" ]] || { echo "ERROR: owner B access cookie missing" >&2; exit 1; }
select_playing_xi() {
  local token="$1" team="$2" player="$3" body status
  body="$(jq -nc --arg team "$team" --arg player "$player" '{teamId:$team,playerId:$player,captain:false,viceCaptain:false,wicketKeeper:false}')"
  status="$(curl -sS -o "$TMP_DIR/playing-xi-response.json" -w '%{http_code}' -X POST "$BASE_URL/matches/$MATCH_ID/playing-xi" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body")"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "ERROR: selected XI update failed HTTP $status team=$team player=$player" >&2
    cat "$TMP_DIR/playing-xi-response.json" >&2
    exit 1
  fi
}
select_playing_xi "$TOKEN" "$TEAM_A_ID" "$A_STRIKER_ID"
select_playing_xi "$TOKEN" "$TEAM_A_ID" "$A_NON_STRIKER_ID"
select_playing_xi "$TOKEN_B" "$TEAM_B_ID" "$B_BOWLER_ID"
select_playing_xi "$TOKEN_B" "$TEAM_B_ID" "$B_STRIKER_ID"
echo "[3/10] Validate fixture Playing XI before toss"
XI="$(request GET "$BASE_URL/matches/$MATCH_ID/playing-xi")" || fail_with_body "GET Playing XI failed"
XI_A_STRIKER="$(jq -r --arg team "$TEAM_A_ID" --arg player "$A_STRIKER_ID" '[.[] | select(.teamId == $team and .playerId == $player)] | length' <<<"$XI")"
XI_A_NON="$(jq -r --arg team "$TEAM_A_ID" --arg player "$A_NON_STRIKER_ID" '[.[] | select(.teamId == $team and .playerId == $player)] | length' <<<"$XI")"
XI_B_BOWLER="$(jq -r --arg team "$TEAM_B_ID" --arg player "$B_BOWLER_ID" '[.[] | select(.teamId == $team and .playerId == $player)] | length' <<<"$XI")"
XI_B_ALT="$(jq -r --arg team "$TEAM_B_ID" --arg player "$B_STRIKER_ID" '[.[] | select(.teamId == $team and .playerId == $player)] | length' <<<"$XI")"
[[ "$XI_A_STRIKER" == "1" && "$XI_A_NON" == "1" && "$XI_B_BOWLER" == "1" && "$XI_B_ALT" == "1" ]] || {
  echo "ERROR: fixture Playing XI mismatch. A striker=$XI_A_STRIKER A non=$XI_A_NON B bowler=$XI_B_BOWLER B alternate=$XI_B_ALT" >&2
  echo "$XI" >&2
  exit 1
}
echo "    PASS: all opening players and alternate bowler are selected"
echo "[3/10] Record toss: Team A bats"
TOSS_BODY="$(jq -nc --arg id "$MATCH_ID" --arg winner "$TEAM_A_ID" '{matchId:$id,winnerTeamId:$winner,decision:"BAT"}')"; request POST "$BASE_URL/matches/$MATCH_ID/toss" "$TOSS_BODY" >/dev/null || fail_with_body "record toss failed" POST "$BASE_URL/matches/$MATCH_ID/toss" "$TOSS_BODY"

echo "[4/10] Start innings 1"
INNINGS1_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_A_ID" --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" '{matchId:$match,inningsNumber:1,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"; INNINGS1="$(request POST "$BASE_URL/scoring/innings" "$INNINGS1_BODY")" || fail_with_body "start innings 1 failed" POST "$BASE_URL/scoring/innings" "$INNINGS1_BODY"; INNINGS1_ID="$(jq -r '.id // .inningsId // empty' <<<"$INNINGS1")"; [[ -n "$INNINGS1_ID" ]] || { echo "ERROR: innings 1 ID missing" >&2; exit 1; }
OPEN1_BODY="$(jq -nc --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" '{strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler}')"; request POST "$BASE_URL/scoring/innings/$INNINGS1_ID/opening" "$OPEN1_BODY" >/dev/null || fail_with_body "opening innings 1 failed" POST "$BASE_URL/scoring/innings/$INNINGS1_ID/opening" "$OPEN1_BODY"
acquire_scorer_lease "$INNINGS1_ID"

echo "[5/10] Score innings 1: 24 legal dot deliveries across four cricket-correct overs"
CURRENT_STRIKER="$A_STRIKER_ID"; CURRENT_NON="$A_NON_STRIKER_ID"; CURRENT_BOWLER="$B_BOWLER_ID"
for ((delivery=1; delivery<=24; delivery++)); do
  if (( delivery > 1 && delivery % 8 == 0 )); then renew_scorer_lease "$INNINGS1_ID"; fi
  OVER_NUMBER=$(( (delivery - 1) / 6 )); BALL_NUMBER=$(( ((delivery - 1) % 6) + 1 )); DELIVERY_BODY="$(jq -nc --arg innings "$INNINGS1_ID" --arg striker "$CURRENT_STRIKER" --arg non "$CURRENT_NON" --arg bowler "$CURRENT_BOWLER" --argjson over "$OVER_NUMBER" --argjson ball "$BALL_NUMBER" '{inningsId:$innings,overNumber:$over,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:0,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"; request POST "$BASE_URL/scoring/innings/$INNINGS1_ID/deliveries" "$DELIVERY_BODY" >/dev/null || fail_with_body "innings 1 delivery $delivery failed" POST "$BASE_URL/scoring/innings/$INNINGS1_ID/deliveries" "$DELIVERY_BODY"; if (( BALL_NUMBER == 6 )); then
    # At over-end, swap ends and rotate to the alternate bowler.
    tmp="$CURRENT_STRIKER"; CURRENT_STRIKER="$CURRENT_NON"; CURRENT_NON="$tmp"
    if [[ "$CURRENT_BOWLER" == "$B_BOWLER_ID" ]]; then CURRENT_BOWLER="$B_STRIKER_ID"; else CURRENT_BOWLER="$B_BOWLER_ID"; fi
    CHECKPOINT="$(request GET "$BASE_URL/scoring/innings/$INNINGS1_ID")" || fail_with_body "over checkpoint GET failed"
    CHECKPOINT_BALLS="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$CHECKPOINT")"
    CHECKPOINT_RUNS="$(jq -r '.runs // .totalRuns // -1' <<<"$CHECKPOINT")"
    CHECKPOINT_WICKETS="$(jq -r '.wickets // -1' <<<"$CHECKPOINT")"
    COMPLETED_OVERS="$(jq '[.overs[]? | select(.completed == true)] | length' <<<"$CHECKPOINT")"
    CHECKPOINT_STRIKER="$(jq -r '.strikerId // .striker_id // empty' <<<"$CHECKPOINT")"
    CHECKPOINT_NON_STRIKER="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$CHECKPOINT")"
    EXPECTED_BOWLER="$B_BOWLER_ID"; if (( OVER_NUMBER % 2 == 1 )); then EXPECTED_BOWLER="$B_STRIKER_ID"; fi
    LAST_OVER_BOWLER="$(jq -r '.overs[-1].bowlerId // .overs[-1].bowler_id // empty' <<<"$CHECKPOINT")"
    [[ "$CHECKPOINT_BALLS" == "$delivery" && "$CHECKPOINT_RUNS" == "0" && "$CHECKPOINT_WICKETS" == "0" && "$COMPLETED_OVERS" == "$((OVER_NUMBER + 1))" && "$LAST_OVER_BOWLER" == "$EXPECTED_BOWLER" && "$CHECKPOINT_STRIKER" == "$CURRENT_STRIKER" && "$CHECKPOINT_NON_STRIKER" == "$CURRENT_NON" ]] || {
      echo "ERROR: over checkpoint failed: over=$OVER_NUMBER legalBalls=$CHECKPOINT_BALLS runs=$CHECKPOINT_RUNS wickets=$CHECKPOINT_WICKETS completedOvers=$COMPLETED_OVERS bowler=$LAST_OVER_BOWLER expectedBowler=$EXPECTED_BOWLER striker=$CHECKPOINT_STRIKER expectedStriker=$CURRENT_STRIKER nonStriker=$CHECKPOINT_NON_STRIKER expectedNonStriker=$CURRENT_NON" >&2
      exit 1
    }
    echo "    PASS: over $((OVER_NUMBER + 1)) completed; 6 legal balls; bowler=$LAST_OVER_BOWLER; score=0/0"
  fi
done
SCORE1="$(request GET "$BASE_URL/scoring/innings/$INNINGS1_ID")"; STATUS1="$(jq -r '.status // empty' <<<"$SCORE1")"; BALLS1="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$SCORE1")"; [[ "$STATUS1" == "COMPLETED" && "$BALLS1" == "24" ]] || { echo "ERROR: innings 1 status=$STATUS1 legalBalls=$BALLS1" >&2; exit 1; }
expect_rejection "cannot restart completed innings 1" POST "$BASE_URL/scoring/innings" "$INNINGS1_BODY"
WRONG_INNINGS2_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_A_ID" --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" '{matchId:$match,inningsNumber:2,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"; expect_rejection "innings 2 rejects wrong batting team" POST "$BASE_URL/scoring/innings" "$WRONG_INNINGS2_BODY"

echo "[6/10] Start innings 2 with Team B"
INNINGS2_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_B_ID" --arg striker "$B_STRIKER_ID" --arg non "$B_NON_STRIKER_ID" --arg bowler "$A_BOWLER_ID" '{matchId:$match,inningsNumber:2,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"; INNINGS2="$(request POST "$BASE_URL/scoring/innings" "$INNINGS2_BODY")" || fail_with_body "start innings 2 failed" POST "$BASE_URL/scoring/innings" "$INNINGS2_BODY"; INNINGS2_ID="$(jq -r '.id // .inningsId // empty' <<<"$INNINGS2")"; [[ -n "$INNINGS2_ID" ]] || { echo "ERROR: innings 2 ID missing" >&2; exit 1; }
OPEN2_BODY="$(jq -nc --arg striker "$B_STRIKER_ID" --arg non "$B_NON_STRIKER_ID" --arg bowler "$A_BOWLER_ID" '{strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler}')"; request POST "$BASE_URL/scoring/innings/$INNINGS2_ID/opening" "$OPEN2_BODY" >/dev/null || fail_with_body "opening innings 2 failed" POST "$BASE_URL/scoring/innings/$INNINGS2_ID/opening" "$OPEN2_BODY"
acquire_scorer_lease "$INNINGS2_ID"

echo "[7/10] Score innings 2: reach target"
DELIVERY2_BODY="$(jq -nc --arg innings "$INNINGS2_ID" --arg striker "$B_STRIKER_ID" --arg non "$B_NON_STRIKER_ID" --arg bowler "$A_BOWLER_ID" '{inningsId:$innings,overNumber:0,ballNumber:1,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:1,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"; request POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY" >/dev/null || fail_with_body "innings 2 delivery failed" POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY"; SCORE2="$(request GET "$BASE_URL/scoring/innings/$INNINGS2_ID")"; STATUS2="$(jq -r '.status // empty' <<<"$SCORE2")"; RUNS2="$(jq -r '.runs // .totalRuns // -1' <<<"$SCORE2")"; BALLS2="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$SCORE2")"; [[ "$STATUS2" == "COMPLETED" && "$RUNS2" == "1" && "$BALLS2" == "1" ]] || { echo "ERROR: innings 2 status=$STATUS2 runs=$RUNS2 legalBalls=$BALLS2" >&2; exit 1; }
expect_rejection "cannot score after match completion" POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY"; expect_rejection "cannot start normal innings 3 after match completion" POST "$BASE_URL/scoring/innings" "$INNINGS2_BODY"

echo "[8/10] Verify scorecard, result and idempotency"
SCORECARD="$(request GET "$BASE_URL/matches/$MATCH_ID/scorecard")"; SCORECARD_INNINGS="$(jq 'length' <<<"$SCORECARD")"; SCORECARD1_RUNS="$(jq -r '.[0].runs // -1' <<<"$SCORECARD")"; SCORECARD1_BALLS="$(jq -r '.[0].legalBalls // -1' <<<"$SCORECARD")"; SCORECARD2_RUNS="$(jq -r '.[1].runs // -1' <<<"$SCORECARD")"; SCORECARD2_BALLS="$(jq -r '.[1].legalBalls // -1' <<<"$SCORECARD")"; [[ "$SCORECARD_INNINGS" == "2" && "$SCORECARD1_RUNS" == "0" && "$SCORECARD1_BALLS" == "24" && "$SCORECARD2_RUNS" == "1" && "$SCORECARD2_BALLS" == "1" ]] || { echo "ERROR: scorecard mismatch innings=$SCORECARD_INNINGS first=$SCORECARD1_RUNS/$SCORECARD1_BALLS second=$SCORECARD2_RUNS/$SCORECARD2_BALLS" >&2; exit 1; }
RESULT1="$(request GET "$BASE_URL/matches/$MATCH_ID/result")"; RESULT2="$(request GET "$BASE_URL/matches/$MATCH_ID/result")"; [[ "$(jq -S -c . <<<"$RESULT1")" == "$(jq -S -c . <<<"$RESULT2")" ]] || { echo "ERROR: result is not idempotent" >&2; exit 1; }; RESULT_STATUS="$(jq -r '.matchStatus // .status // empty' <<<"$RESULT1")"; RESULT_TYPE="$(jq -r '.detailedResultType // .resultType // empty' <<<"$RESULT1")"; WINNER_TEAM_ID="$(jq -r '.winningTeamId // .winnerTeamId // empty' <<<"$RESULT1")"; [[ "$RESULT_STATUS" == "COMPLETED" ]] || { echo "ERROR: result status=$RESULT_STATUS" >&2; exit 1; }; [[ "$RESULT_TYPE" == "WIN_BY_WICKETS" || "$RESULT_TYPE" == "WIN_BY_RUNS" || "$RESULT_TYPE" == "TIE" ]] || { echo "ERROR: unexpected result type=$RESULT_TYPE" >&2; exit 1; }; [[ "$WINNER_TEAM_ID" == "$TEAM_B_ID" ]] || { echo "ERROR: expected Team B winner=$WINNER_TEAM_ID" >&2; exit 1; }

echo "[9/10] Verify final match and tournament state"
MATCH_FINAL="$(request GET "$BASE_URL/matches/$MATCH_ID")"; FINAL_STATUS="$(jq -r '.status // empty' <<<"$MATCH_FINAL")"; [[ "$FINAL_STATUS" == "COMPLETED" ]] || { echo "ERROR: final match status=$FINAL_STATUS" >&2; exit 1; }
if [[ -n "$TOURNAMENT_ID" ]]; then
  POINTS="$(request GET "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table")"; TEAM_A_ROW="$(jq -c --arg team "$TEAM_A_ID" '.[] | select((.teamId // .team_id // .team?.id // "") == $team)' <<<"$POINTS" | head -n1)"; TEAM_B_ROW="$(jq -c --arg team "$TEAM_B_ID" '.[] | select((.teamId // .team_id // .team?.id // "") == $team)' <<<"$POINTS" | head -n1)"; [[ -n "$TEAM_A_ROW" && -n "$TEAM_B_ROW" ]] || { echo "ERROR: tournament teams missing from points table" >&2; exit 1; }; TEAM_A_PLAYED="$(jq -r '.played // .matchesPlayed // 0' <<<"$TEAM_A_ROW")"; TEAM_B_PLAYED="$(jq -r '.played // .matchesPlayed // 0' <<<"$TEAM_B_ROW")"; TEAM_A_POINTS="$(jq -r '.points // 0' <<<"$TEAM_A_ROW")"; TEAM_B_POINTS="$(jq -r '.points // 0' <<<"$TEAM_B_ROW")"; [[ "$TEAM_A_PLAYED" == "1" && "$TEAM_B_PLAYED" == "1" && "$TEAM_A_POINTS" == "0" && "$TEAM_B_POINTS" == "2" ]] || { echo "ERROR: points mismatch A=$TEAM_A_PLAYED/$TEAM_A_POINTS B=$TEAM_B_PLAYED/$TEAM_B_POINTS" >&2; exit 1; }; COMPLETED_TOURNAMENT="$(request PATCH "$BASE_URL/tournaments/$TOURNAMENT_ID/status" '{"status":"COMPLETED"}')"; TOURNAMENT_STATUS="$(jq -r '.status // empty' <<<"$COMPLETED_TOURNAMENT")"; [[ "$TOURNAMENT_STATUS" == "COMPLETED" ]] || { echo "ERROR: tournament did not complete: $TOURNAMENT_STATUS" >&2; exit 1; }; echo "    tournament completed=$TOURNAMENT_ID";
else echo "    tournament verification skipped"; fi

echo "[10/10] Regression summary"; echo "PASS: complete two-innings scoring + tournament lifecycle"; echo "    match=$MATCH_ID"; echo "    innings1=$INNINGS1_ID status=$STATUS1 legalBalls=$BALLS1"; echo "    innings2=$INNINGS2_ID status=$STATUS2"; echo "    resultType=$RESULT_TYPE winnerTeamId=$WINNER_TEAM_ID"; echo "    finalMatchStatus=$FINAL_STATUS"