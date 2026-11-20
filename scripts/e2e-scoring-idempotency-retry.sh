#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
: "${EMAIL:?EMAIL is required}" "${PASSWORD:?PASSWORD is required}" "${OWNER_B_EMAIL:?OWNER_B_EMAIL is required}"
: "${TEAM_A_ID:?TEAM_A_ID is required}" "${TEAM_B_ID:?TEAM_B_ID is required}"
: "${A_STRIKER_ID:?A_STRIKER_ID is required}" "${A_NON_STRIKER_ID:?A_NON_STRIKER_ID is required}" "${B_BOWLER_ID:?B_BOWLER_ID is required}"

tmp="$(mktemp -d)"
ws_monitor_pid=""
trap 'if [[ -n "$ws_monitor_pid" ]]; then kill "$ws_monitor_pid" 2>/dev/null || true; fi; rm -rf "$tmp"' EXIT
# Cookie-based login: each identity gets its own CSRF and HttpOnly cookie jar.
login_token() {
  local email="$1" jar="$2" csrf token
  curl -fsS -b "$jar" -c "$jar" "$BASE_URL/auth/csrf" >/dev/null
  csrf="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$jar")"
  [[ -n "$csrf" ]] || { echo "CSRF cookie missing for $email" >&2; return 1; }
  curl -fsS -b "$jar" -c "$jar" -X POST "$BASE_URL/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
    --data "$(jq -nc --arg email "$email" --arg password "$PASSWORD" '{email:$email,password:$password}')" >/dev/null
  token="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$jar")"
  [[ -n "$token" ]] || { echo "HttpOnly access cookie missing after login for $email" >&2; return 1; }
  printf '%s' "$token"
}
JAR_A="$tmp/owner-a.cookies"
JAR_B="$tmp/owner-b.cookies"
token="$(login_token "$EMAIL" "$JAR_A")"
test -n "$token"
auth=(-H "Authorization: Bearer $token" -H 'Content-Type: application/json')
owner_b_token="$(login_token "$OWNER_B_EMAIL" "$JAR_B")"
test -n "$owner_b_token"
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

lease_token="$(cat /proc/sys/kernel/random/uuid)"
lease_body="$(jq -nc --arg token "$lease_token" '{leaseToken:$token}')"
curl -fsS -X POST "$BASE_URL/scoring/innings/$innings_id/lease" "${auth[@]}" --data "$lease_body" >/dev/null
lease=(-H "X-Scorer-Lease: $lease_token")

# A second authorized team owner must not acquire an active innings lease.
other_lease_token="$(cat /proc/sys/kernel/random/uuid)"
other_lease_body="$(jq -nc --arg token "$other_lease_token" '{leaseToken:$token}')"
lease_conflict_status="$(curl -sS -o "$tmp/lease-conflict.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/lease" "${auth_b[@]}" --data "$other_lease_body")"
if [[ "$lease_conflict_status" != "409" ]]; then
  echo "FAIL: second scorer expected HTTP 409 lease conflict, received $lease_conflict_status" >&2
  cat "$tmp/lease-conflict.json" >&2
  exit 1
fi

echo "[3/7] Connect public live-score WebSocket subscription"
export WS_URL="${WS_URL:-${BASE_URL%/api}/ws}"
export WS_URL="${WS_URL/http:\/\//ws:\/\/}"
export WS_URL="${WS_URL/https:\/\//wss:\/\/}"
export INNINGS_ID="$innings_id"
export WS_READY_FILE="$tmp/ws-ready"
export WS_DONE_FILE="$tmp/ws-done"
export WS_OUTPUT_FILE="$tmp/ws-result.json"
node scripts/e2e-scoring-websocket.mjs >"$tmp/ws-monitor.log" 2>&1 &
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

# Baseline snapshot: undo must restore all authoritative scoring state, not only totals.
baseline_score="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
public_baseline_score="$(curl -fsS "$BASE_URL/public/innings/$innings_id")"
baseline_version="$(jq -r '.eventVersion // -1' <<<"$baseline_score")"
public_baseline_version="$(jq -r '.eventVersion // -1' <<<"$public_baseline_score")"
if [[ "$baseline_version" -lt 0 || "$public_baseline_version" != "$baseline_version" ]]; then
  echo "FAIL: public recovery snapshot must expose the same authoritative eventVersion as the scorer snapshot" >&2
  echo "Scorer version=$baseline_version public version=$public_baseline_version" >&2
  exit 1
fi
baseline_projection="$(jq -S -c '{runs:(.runs // .totalRuns // .total_runs // 0),legalBalls:(.legalBalls // .legal_balls // 0),wickets:(.wickets // 0),recentBalls:(.recentBalls // []),overs:(.overs // []),strikerId:(.strikerId // .striker_id // null),nonStrikerId:(.nonStrikerId // .non_striker_id // null),currentBowlerId:(.currentBowlerId // .current_bowler_id // null)}' <<<"$baseline_score")"

delivery="$(jq -nc --arg innings "$innings_id" --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" '{inningsId:$innings,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,overNumber:0,ballNumber:1,batRuns:1,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"
command_id="$(cat /proc/sys/kernel/random/uuid)"

echo "[4/7] Reject stale lease and concurrently replay the same command ID"
stale_lease_token="$(cat /proc/sys/kernel/random/uuid)"
stale_status="$(curl -sS -o "$tmp/stale-lease.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "${auth[@]}" -H "X-Scorer-Lease: $stale_lease_token" -H "X-Command-Id: $command_id" --data "$delivery")"
if [[ "$stale_status" -lt 400 || "$stale_status" -ge 500 ]]; then
  echo "FAIL: stale lease should be rejected with 4xx, received HTTP $stale_status" >&2
  cat "$tmp/stale-lease.json" >&2
  exit 1
fi

# Simulate two scorer clients retrying after a lost response at the same time.
# Both carry the same idempotency key; only one event may be committed.
curl -sS -o "$tmp/concurrent-a.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "${auth[@]}" "${lease[@]}" -H "X-Command-Id: $command_id" --data "$delivery" >"$tmp/concurrent-a.status" &
request_a_pid=$!
curl -sS -o "$tmp/concurrent-b.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "${auth[@]}" "${lease[@]}" -H "X-Command-Id: $command_id" --data "$delivery" >"$tmp/concurrent-b.status" &
request_b_pid=$!

wait "$request_a_pid"
wait "$request_b_pid"
status_a="$(cat "$tmp/concurrent-a.status")"
status_b="$(cat "$tmp/concurrent-b.status")"
if [[ ! "$status_a" =~ ^2[0-9][0-9]$ || ! "$status_b" =~ ^2[0-9][0-9]$ ]]; then
  echo "FAIL: concurrent idempotent requests must both be acknowledged, received HTTP $status_a and $status_b" >&2
  cat "$tmp/concurrent-a.json" "$tmp/concurrent-b.json" >&2
  exit 1
fi
first="$(cat "$tmp/concurrent-a.json")"
score_after_first="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_first="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_first")"
runs_first="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_first")"
delivery_count_first="$(jq -r '.recentBalls | length' <<<"$score_after_first")"
delivery_version="$(jq -r '.eventVersion // -1' <<<"$score_after_first")"
public_delivery_score="$(curl -fsS "$BASE_URL/public/innings/$innings_id")"
public_delivery_version="$(jq -r '.eventVersion // -1' <<<"$public_delivery_score")"
if [[ "$delivery_version" -le "$baseline_version" || "$public_delivery_version" != "$delivery_version" ]]; then
  echo "FAIL: public REST recovery did not expose the committed delivery version" >&2
  echo "Baseline=$baseline_version scorer=$delivery_version public=$public_delivery_version" >&2
  exit 1
fi
if [[ "$balls_first" != "1" || "$runs_first" != "1" || "$delivery_count_first" != "1" ]]; then
  echo "FAIL: concurrent replay created duplicate score/events: score=$runs_first/$balls_first deliveries=$delivery_count_first" >&2
  cat "$tmp/concurrent-a.json" "$tmp/concurrent-b.json" >&2
  exit 1
fi
echo "PASS: concurrent duplicate requests acknowledged; one delivery persisted (HTTP $status_a/$status_b)"

echo "[5/7] Replay exact same delivery with same command ID"
retry="$(curl -fsS -X POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "${auth[@]}" "${lease[@]}" -H "X-Command-Id: $command_id" --data "$delivery")"
score_after_retry="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_retry="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_retry")"
runs_retry="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_retry")"
if [[ "$balls_retry" != "$balls_first" || "$runs_retry" != "$runs_first" ]]; then
  echo "FAIL: duplicate command changed score: before=$runs_first/$balls_first after=$runs_retry/$balls_retry" >&2
  echo "first response: $first" >&2
  echo "retry response: $retry" >&2
  exit 1
fi

echo "[6/7] Verify conflicting payload under the same command ID cannot mutate scoring state"
conflicting_delivery="$(jq -c '.batRuns = 2' <<<"$delivery")"
conflict_status="$(curl -sS -o "$tmp/conflict-response.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/deliveries" "${auth[@]}" "${lease[@]}" -H "X-Command-Id: $command_id" --data "$conflicting_delivery")"
conflict_response="$(cat "$tmp/conflict-response.json")"
if [[ ! "$conflict_status" =~ ^[234][0-9][0-9]$ ]]; then
  echo "FAIL: conflicting command ID returned unexpected HTTP $conflict_status" >&2
  echo "Response: $conflict_response" >&2
  exit 1
fi
score_after_conflict="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_conflict="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_conflict")"
runs_conflict="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_conflict")"
conflict_delivery_count="$(jq -r '.recentBalls | length' <<<"$score_after_conflict")"
conflict_record_bat_runs="$(jq -r '.recentBalls[0].batRuns // -1' <<<"$score_after_conflict")"
conflict_record_total_runs="$(jq -r '.recentBalls[0].totalRuns // -1' <<<"$score_after_conflict")"
if [[ "$balls_conflict" != "$balls_first" || "$runs_conflict" != "$runs_first" || "$conflict_delivery_count" != "1" || "$conflict_record_bat_runs" != "1" || "$conflict_record_total_runs" != "1" ]]; then
  echo "FAIL: conflicting command mutated scoring state" >&2
  echo "Expected score=$runs_first/$balls_first deliveries=1 batRuns=1 totalRuns=1" >&2
  echo "Actual score=$runs_conflict/$balls_conflict deliveries=$conflict_delivery_count batRuns=$conflict_record_bat_runs totalRuns=$conflict_record_total_runs" >&2
  echo "Response: $conflict_response" >&2
  exit 1
fi
echo "PASS: conflicting payload did not mutate score, ball count, or delivery record (HTTP $conflict_status)"

echo "[7/7] Undo delivery and verify score rollback"
undo_status="$(curl -sS -o "$tmp/undo-response.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/undo" "${auth[@]}" "${lease[@]}")"
undo_response="$(cat "$tmp/undo-response.json")"
if [[ ! "$undo_status" =~ ^2[0-9][0-9]$ ]]; then
  echo "FAIL: undo delivery returned HTTP $undo_status" >&2
  echo "Response: $undo_response" >&2
  exit 1
fi
score_after_undo="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_undo="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_undo")"
runs_undo="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_undo")"
undo_projection="$(jq -S -c '{runs:(.runs // .totalRuns // .total_runs // 0),legalBalls:(.legalBalls // .legal_balls // 0),wickets:(.wickets // 0),recentBalls:(.recentBalls // []),overs:(.overs // []),strikerId:(.strikerId // .striker_id // null),nonStrikerId:(.nonStrikerId // .non_striker_id // null),currentBowlerId:(.currentBowlerId // .current_bowler_id // null)}' <<<"$score_after_undo")"
undo_version="$(jq -r '.eventVersion // -1' <<<"$score_after_undo")"
public_undo_score="$(curl -fsS "$BASE_URL/public/innings/$innings_id")"
public_undo_version="$(jq -r '.eventVersion // -1' <<<"$public_undo_score")"
if [[ "$undo_version" -le "$delivery_version" || "$public_undo_version" != "$undo_version" ]]; then
  echo "FAIL: public REST recovery did not expose the newer undo version" >&2
  echo "Delivery=$delivery_version scorerUndo=$undo_version publicUndo=$public_undo_version" >&2
  exit 1
fi
if [[ "$balls_undo" != "0" || "$runs_undo" != "0" || "$undo_projection" != "$baseline_projection" ]]; then
  echo "FAIL: undo failed to restore complete scoring snapshot" >&2
  echo "Baseline: $baseline_projection" >&2
  echo "After undo: $undo_projection" >&2
  echo "Undo response: $undo_response" >&2
  echo "Score response: $score_after_undo" >&2
  exit 1
fi

# Undo on an innings with no remaining deliveries must be a safe no-op.
undo_empty_status="$(curl -sS -o "$tmp/undo-empty-response.json" -w '%{http_code}' -X POST "$BASE_URL/scoring/innings/$innings_id/undo" "${auth[@]}" "${lease[@]}")"
if [[ ! "$undo_empty_status" =~ ^2[0-9][0-9]$ ]]; then
  echo "FAIL: empty undo returned HTTP $undo_empty_status" >&2
  cat "$tmp/undo-empty-response.json" >&2
  exit 1
fi
score_after_empty_undo="$(curl -fsS "$BASE_URL/scoring/innings/$innings_id" "${auth[@]}")"
balls_empty_undo="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$score_after_empty_undo")"
runs_empty_undo="$(jq -r '.runs // .totalRuns // .total_runs // -1' <<<"$score_after_empty_undo")"
empty_undo_projection="$(jq -S -c '{runs:(.runs // .totalRuns // .total_runs // 0),legalBalls:(.legalBalls // .legal_balls // 0),wickets:(.wickets // 0),recentBalls:(.recentBalls // []),overs:(.overs // []),strikerId:(.strikerId // .striker_id // null),nonStrikerId:(.nonStrikerId // .non_striker_id // null),currentBowlerId:(.currentBowlerId // .current_bowler_id // null)}' <<<"$score_after_empty_undo")"
empty_undo_version="$(jq -r '.eventVersion // -1' <<<"$score_after_empty_undo")"
if [[ "$empty_undo_version" != "$undo_version" ]]; then
  echo "FAIL: no-op undo must not advance the authoritative eventVersion" >&2
  echo "Undo version=$undo_version empty-undo version=$empty_undo_version" >&2
  exit 1
fi
if [[ "$balls_empty_undo" != "0" || "$runs_empty_undo" != "0" || "$empty_undo_projection" != "$baseline_projection" ]]; then
  echo "FAIL: empty undo changed authoritative scoring state" >&2
  echo "Baseline: $baseline_projection" >&2
  echo "After empty undo: $empty_undo_projection" >&2
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
