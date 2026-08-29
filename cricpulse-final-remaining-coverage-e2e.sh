#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-rahul.test2026@gmail.com}"
PASSWORD="${PASSWORD:-Test@12345}"
INNINGS_ID="${INNINGS_ID:?ERROR: Set INNINGS_ID to a LIVE innings UUID}"

echo "============================================================"
echo " CRICPULSE FINAL REMAINING SCORING + WICKET COVERAGE E2E"
echo "============================================================"

command -v curl >/dev/null || { echo "ERROR: curl required"; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq required"; exit 1; }

api() {
  local method="$1" path="$2" data="${3:-}"
  if [[ -n "$data" ]]; then
    curl -sS -w $'\n%{http_code}' -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$data"
  else
    curl -sS -w $'\n%{http_code}' -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $TOKEN"
  fi
}
split_http(){ HTTP="${1##*$'\n'}"; BODY="${1%$'\n'*}"; }
score(){ split_http "$(api GET "/scoring/innings/$INNINGS_ID")"; [[ "$HTTP" == 200 ]] || { echo "[FAIL] score HTTP $HTTP $BODY"; exit 1; }; SCORE="$BODY"; }
field(){ jq -r "$1" <<<"$SCORE"; }

login="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
split_http "$login"; [[ "$HTTP" == 200 ]] || { echo "[FAIL] Login $HTTP"; echo "$BODY"; exit 1; }
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$BODY")"
[[ -n "$TOKEN" ]] || { echo "[FAIL] JWT missing"; exit 1; }
echo "[PASS] Authentication"

score
STATUS="$(field '.status')"; MATCH_ID="$(field '.matchId // .match_id // empty')"; W0="$(field '.wickets // 0')"
[[ "$STATUS" == LIVE ]] || { echo "[FAIL] Innings must be LIVE"; exit 1; }
[[ "$W0" -le 3 ]] || { echo "[FAIL] Need a fresh innings with <=3 wickets (6 remaining wicket tests need room)"; exit 1; }

XI="$(api GET "/scoring/innings/$INNINGS_ID/playing-xi")"; split_http "$XI"; [[ "$HTTP" == 200 ]] || { echo "[FAIL] Playing XI $HTTP"; exit 1; }; XI="$BODY"

new_batter() {
  local striker="$1" non="$2"
  local used
  used="$(jq -c --arg s "$striker" --arg n "$non" '[.batters[]? | select((.isOut // .is_out // false)==true) | (.playerId // .player_id)] + [$s,$n]' <<<"$SCORE")"
  jq -r --arg team "$(jq -r --arg s "$striker" 'first(.batters[]? | select((.playerId // .player_id)==$s) | (.teamId // .team_id)) // empty' <<<"$SCORE")" --argjson used "$used" '
    first(.[] | (.playerId // .player_id) as $id | select($id!=null) | select(($used|index($id))==null) | $id) // empty' <<<"$XI"
}

record_normal() {
  local name="$1" bat="$2" extra="$3" etype="$4"
  score
  local lb runs s n b over ball payload beforelb beforeruns
  lb="$(field '.legalBalls // .legal_balls // 0')"; runs="$(field '.runs // .totalRuns // .total_runs // 0')"
  s="$(field '.strikerId // .striker_id')"; n="$(field '.nonStrikerId // .non_striker_id')"; b="$(field '.currentBowlerId // .current_bowler_id')"
  over=$((lb/6)); ball=$((lb%6+1))
  echo; echo "---- CASE: $name ----"
  payload="$(jq -nc --arg iid "$INNINGS_ID" --arg s "$s" --arg n "$n" --arg b "$b" --arg et "$etype" --argjson o "$over" --argjson bn "$ball" --argjson br "$bat" --argjson er "$extra" '{inningsId:$iid,overNumber:$o,ballNumber:$bn,strikerId:$s,nonStrikerId:$n,bowlerId:$b,batRuns:$br,extraRuns:$er} + (if $et=="" then {} else {extraType:$et} end)')"
  split_http "$(api POST "/scoring/innings/$INNINGS_ID/deliveries" "$payload")"; [[ "$HTTP" == 200 ]] || { echo "[FAIL] $name HTTP $HTTP $BODY"; exit 1; }
  score
  local alb ar
  alb="$(field '.legalBalls // .legal_balls // 0')"; ar="$(field '.runs // .totalRuns // .total_runs // 0')"
  local expectedlb
  if [[ "$etype" == "WIDE" || "$etype" == "NO_BALL" ]]; then
    expectedlb="$lb"
  else
    expectedlb=$((lb + 1))
  fi
  [[ "$alb" -eq "$expectedlb" ]] || { echo "[FAIL] $name legal balls expected=$expectedlb actual=$alb"; exit 1; }
  [[ "$ar" -eq $((runs+bat+extra)) ]] || { echo "[FAIL] $name runs mismatch"; exit 1; }
  echo "[PASS] $name state verified"
}

record_wicket() {
  local type="$1"
  score
  local lb runs wk s n b nb over ball payload
  lb="$(field '.legalBalls // .legal_balls // 0')"; runs="$(field '.runs // .totalRuns // .total_runs // 0')"; wk="$(field '.wickets // 0')"
  s="$(field '.strikerId // .striker_id')"; n="$(field '.nonStrikerId // .non_striker_id')"; b="$(field '.currentBowlerId // .current_bowler_id')"
  nb="$(new_batter "$s" "$n")"; [[ -n "$nb" && "$nb" != null ]] || { echo "[FAIL] No new batter for $type"; exit 1; }
  over=$((lb/6)); ball=$((lb%6+1))
  echo; echo "---- WICKET CASE: $type ----"
  payload="$(jq -nc --arg iid "$INNINGS_ID" --arg s "$s" --arg n "$n" --arg b "$b" --arg type "$type" --arg nb "$nb" --argjson o "$over" --argjson bn "$ball" '{inningsId:$iid,overNumber:$o,ballNumber:$bn,strikerId:$s,nonStrikerId:$n,bowlerId:$b,batRuns:0,extraRuns:0,wicketType:$type,dismissedPlayerId:$s,newBatterId:$nb}')"
  split_http "$(api POST "/scoring/innings/$INNINGS_ID/deliveries" "$payload")"; [[ "$HTTP" == 200 ]] || { echo "[FAIL] $type HTTP $HTTP $BODY"; exit 1; }
  score
  local aw al ar isout dtype fow recent
  aw="$(field '.wickets // 0')"; al="$(field '.legalBalls // .legal_balls // 0')"; ar="$(field '.runs // .totalRuns // .total_runs // 0')"
  [[ "$aw" -eq $((wk+1)) && "$al" -eq $((lb+1)) && "$ar" -eq "$runs" ]] || { echo "[FAIL] $type innings mutation mismatch"; exit 1; }
  isout="$(jq -r --arg id "$s" 'first(.batters[]? | select((.playerId // .player_id)==$id) | (.out // .isOut // .is_out // false)) // false' <<<"$SCORE")"
  dtype="$(jq -r --arg id "$s" 'first(.batters[]? | select((.playerId // .player_id)==$id) | (.dismissalType // .dismissal_type)) // empty' <<<"$SCORE")"
  [[ "$isout" == true && "$dtype" == "$type" ]] || { echo "[FAIL] $type batter dismissal mismatch"; exit 1; }
  echo "[PASS] $type wicket + batter state verified"
}

record_normal "BOUNDARY_FOUR" 4 0 ""
record_normal "SIX" 6 0 ""
record_normal "MULTI_RUN_WIDE" 0 2 "WIDE"
record_normal "NO_BALL_PLUS_BAT_RUN" 1 1 "NO_BALL"
record_normal "PENALTY_RUNS" 0 5 "PENALTY"

for type in BOWLED CAUGHT LBW STUMPED HIT_WICKET TIMED_OUT; do
  record_wicket "$type"
done

echo
echo "---- CASE: UNDO LAST DELIVERY ----"
score
PRE_UNDO_W="$(field '.wickets // 0')"; PRE_UNDO_L="$(field '.legalBalls // .legal_balls // 0')"; PRE_UNDO_R="$(field '.runs // .totalRuns // .total_runs // 0')"
split_http "$(api POST "/scoring/innings/$INNINGS_ID/undo")"; [[ "$HTTP" == 200 ]] || { echo "[FAIL] Undo HTTP $HTTP $BODY"; exit 1; }
score
POST_W="$(field '.wickets // 0')"; POST_L="$(field '.legalBalls // .legal_balls // 0')"; POST_R="$(field '.runs // .totalRuns // .total_runs // 0')"; POST_STATUS="$(field '.status')"
[[ "$POST_W" -eq $((PRE_UNDO_W-1)) ]] || { echo "[FAIL] Undo wicket rollback failed"; exit 1; }
[[ "$POST_L" -eq $((PRE_UNDO_L-1)) ]] || { echo "[FAIL] Undo legal ball rollback failed"; exit 1; }
[[ "$POST_R" -eq "$PRE_UNDO_R" && "$POST_STATUS" == LIVE ]] || { echo "[FAIL] Undo score/status rollback failed"; exit 1; }
echo "[PASS] Undo last wicket rollback verified"

echo
echo "---- CASE: INVALID WIDE WITH BAT RUN ----"
score
lb="$(field '.legalBalls // .legal_balls // 0')"; s="$(field '.strikerId // .striker_id')"; n="$(field '.nonStrikerId // .non_striker_id')"; b="$(field '.currentBowlerId // .current_bowler_id')"
payload="$(jq -nc --arg iid "$INNINGS_ID" --arg s "$s" --arg n "$n" --arg b "$b" --argjson o $((lb/6)) --argjson bn $((lb%6+1)) '{inningsId:$iid,overNumber:$o,ballNumber:$bn,strikerId:$s,nonStrikerId:$n,bowlerId:$b,batRuns:1,extraRuns:1,extraType:"WIDE"}')"
split_http "$(api POST "/scoring/innings/$INNINGS_ID/deliveries" "$payload")"
[[ "$HTTP" == 400 ]] || { echo "[FAIL] Invalid WIDE expected 400 actual=$HTTP $BODY"; exit 1; }
echo "[PASS] Invalid WIDE validation verified"

echo
echo "============================================================"
echo " FINAL REMAINING SCORING COVERAGE E2E PASSED"
echo " Passed: boundaries, multi-wide, no-ball+bat, penalty,"
echo " BOWLED, CAUGHT, LBW, STUMPED, HIT_WICKET, TIMED_OUT, undo,"
echo " invalid-wide validation"
echo "============================================================"
echo "INNINGS_ID=$INNINGS_ID"
