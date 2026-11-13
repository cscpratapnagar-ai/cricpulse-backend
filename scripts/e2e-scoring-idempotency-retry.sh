#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
: "${EMAIL:?EMAIL is required}" "${PASSWORD:?PASSWORD is required}" "${OWNER_B_EMAIL:?OWNER_B_EMAIL is required}"
: "${TEAM_A_ID:?TEAM_A_ID is required}" "${TEAM_B_ID:?TEAM_B_ID is required}"
: "${A_STRIKER_ID:?A_STRIKER_ID is required}" "${A_NON_STRIKER_ID:?A_NON_STRIKER_ID is required}" "${B_BOWLER_ID:?B_BOWLER_ID is required}"

tmp="$(mktemp -d)"
ws_monitor_pid=""
trap 'if [[ -n "$ws_monitor_pid" ]]; then kill "$ws_monitor_pid" 2>/dev/null || true; fi; rm -rf "$tmp"' EXIT
login="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
token="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$login")"
test -n "$token" && test "$token" != null
auth=(-H "Authorization: Bearer $token" -H 'Content-Type: application/json')
owner_b_login="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg email "$OWNER_B_EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
owner_b_token="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$owner_b_login")"
test -n "$owner_b_token" && test "$owner_b_token" != null
auth_b=(-H "Authorization: Bearer $owner_b_token" -H 'Content-Type: application/json')

echo "[1/5] Create isolated disposable match"
match="$(curl -fsS -X POST "$BASE_URL/matches" "${auth[@]}" --data "$(jq -nc --arg name "CricPulse idempotency E2E $(date -u +%s)" --arg a "$TEAM_A_ID" --arg b "$TEAM_B_ID" '{name:$name,teamAId:$a,teamBId:$b,format:"T20",totalOvers:20}')")"
match_id="$(jq -r '.id // empty' <<<"$match")"
test -n "$match_id" && test "$match_id" != null

post_json() {
  local label="$1" url="$2" body="$3" response status
  status="$(curl -sS -o "$tmp/response.json" -w '%{http_code}' -X POST "$url" "${auth[@]}" --data "$body")"
  response="$(cat "$tmp/response.json")"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "FAIL: $label returned HTTP $status" >&2
    echo "Request: $body" >&2
    echo "Response: $response" >&2
    exit 1
  fi
  printf '%s' "$response"
}

echo "[2/5] Select Playing XI, toss and start innings"
select_xi() {
  local -n request_auth="$1"
  local team_id="$2" player_id="$3" body status response
  body="$(jq -nc --arg team "$team_id" --arg player "$player_id" '{teamId:$team,playerId:$player,captain:false,viceCaptain:false,wicketKeeper:false}')"
  status="$(curl -sS -o "$tmp/xi-response.json" -w '%{http_code}' -X POST "$BASE_URL/matches/$match_id/playing-xi" "${request_auth[@]}" --data "$body")"
  response="$(cat "$tmp/xi-response.json")"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "FAIL: select Playing XI returned HTTP $status" >&2
    echo "Request: $body" >&2
    echo "Response: $response" >&2
    exit 1
  fi
}
select_xi auth "$TEAM_A_ID" "$A_STRIKER_ID"
select_xi auth "$TEAM_A_ID" "$A_NON_STRIKER_ID"
select_xi auth_b "$TEAM_B_ID" "$B_BOWLER_ID"
toss_body="$(jq -nc --arg id "$match_id" --arg team "$TEAM_A_ID" '{matchId:$id,winnerTeamId:$team,decision:"BAT"}')"
post_json "record toss" "$BASE_URL/matches/$match_id/toss" "$toss_body" >/dev/null
innings_body="$(jq -nc --arg match "$match_id" --arg team "$TEAM_A_ID" --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" '{matchId:$match,inningsNumber:1,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
innings="$(post_json "start innings" "$BASE_URL/scoring/innings" "$innings_body")"
innings_id="$(jq -r '.id // .inningsId // empty' <<<"$innings")"
test -n "$innings_id" && test "$innings_id" != null
opening_body="$(jq -nc --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" '{strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler}')"
post_json "open innings" "$BASE_URL/scoring/innings/$innings_id/opening" "$opening_body" >/dev/null

echo "[3/7] Connect public live-score WebSocket subscription"
export WS_URL="${WS_URL:-${BASE_URL%/api}/ws}"
export INNINGS_ID="$innings_id"
export WS_READY_FILE="$tmp/ws-ready"
export WS_DONE_FILE="$tmp/ws-done"
export WS_OUTPUT_FILE="$tmp/ws-result.json"
node scripts/e2e-scoring-websocket.js >"$tmp/ws-monitor.log" 2>&1 &
ws_monitor_pid=$!
for attempt in {1..150}; do
  if [[ -f "$WS_READY_FILE" ]]; then break; fi
  if ! kill -0 "$ws_monitor_pid" 2>/dev/null; then
    cat "$tmp/ws-monitor.log" >&2
    exit 1
  fi
  sleep 0.1
done
if [[ ! -f "$WS_READY_FILE" ]]; then
  echo "FAIL: WebSocket subscription did not become ready" >&2
  cat "$tmp/ws-monitor.log" >&2
  exit 1
fi

delivery="$(jq -nc --arg innings "$innings_id" --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" '{inningsId:$innings,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,overNumber:0,ballNumber:1,batRuns:1,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"
command_id="$(cat /proc/sys/kernel/random/uuid)"

echo "[4/7] Submit delivery once with command ID"
first="$(curl -fsS -X POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "${auth[@]}" -H "X-Command-Id: $command_id" --data "$delivery")"
score_after_first="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_first="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_first")"
runs_first="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_first")"
test "$balls_first" = "1"
test "$runs_first" = "1"

echo "[5/7] Replay exact same delivery with same command ID"
retry="$(curl -fsS -X POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "${auth[@]}" -H "X-Command-Id: $command_id" --data "$delivery")"
score_after_retry="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_retry="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_retry")"
runs_retry="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_retry")"
if [[ "$balls_retry" != "$balls_first" || "$runs_retry" != "$runs_first" ]]; then
  echo "FAIL: duplicate command changed score: before=$runs_first/$balls_first after=$runs_retry/$balls_retry" >&2
  echo "first response: $first" >&2
  echo "retry response: $retry" >&2
  exit 1
fi

echo "[6/7] PASS: same HTTP command replay did not double-apply delivery"

echo "[7/7] Undo delivery and verify score rollback"
undo_status="$(curl -sS -o "$tmp/undo-response.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/undo" "${auth[@]}")"
undo_response="$(cat "$tmp/undo-response.json")"
if [[ ! "$undo_status" =~ ^2[0-9][0-9]$ ]]; then
  echo "FAIL: undo delivery returned HTTP $undo_status" >&2
  echo "Response: $undo_response" >&2
  exit 1
fi
score_after_undo="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_undo="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_undo")"
runs_undo="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_undo")"
if [[ "$balls_undo" != "0" || "$runs_undo" != "0" ]]; then
  echo "FAIL: undo did not restore score to 0/0: runs=$runs_undo legalBalls=$balls_undo" >&2
  echo "Undo response: $undo_response" >&2
  echo "Score response: $score_after_undo" >&2
  exit 1
fi

# Undo on an innings with no remaining deliveries must be a safe no-op.
undo_empty_status="$(curl -sS -o "$tmp/undo-empty-response.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/undo" "${auth[@]}")"
if [[ ! "$undo_empty_status" =~ ^2[0-9][0-9]$ ]]; then
  echo "FAIL: empty undo returned HTTP $undo_empty_status" >&2
  cat "$tmp/undo-empty-response.json" >&2
  exit 1
fi
score_after_empty_undo="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_empty_undo="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_empty_undo")"
runs_empty_undo="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_empty_undo")"
if [[ "$balls_empty_undo" != "0" || "$runs_empty_undo" != "0" ]]; then
  echo "FAIL: empty undo changed score: runs=$runs_empty_undo legalBalls=$balls_empty_undo" >&2
  exit 1
fi

echo "PASS: undo restored score to 0/0 and empty undo was a no-op"
echo "match=$match_id innings=$innings_id commandId=$command_id runsAfterUndo=$runs_undo legalBallsAfterUndo=$balls_undo"

# Let the WebSocket monitor inspect the complete delivery/retry/undo sequence.
touch "$WS_DONE_FILE"
if ! wait "$ws_monitor_pid"; then
  cat "$tmp/ws-monitor.log" >&2
  cat "$WS_OUTPUT_FILE" >&2 || true
  exit 1
fi
ws_monitor_pid=""
cat "$tmp/ws-monitor.log"
