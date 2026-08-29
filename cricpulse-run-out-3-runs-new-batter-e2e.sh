#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-rahul.test2026@gmail.com}"
PASSWORD="${PASSWORD:-Test@12345}"
INNINGS_ID="${INNINGS_ID:-7808faa1-3d32-493f-a19d-1dfc138a93e7}"

WICKET_TYPE="RUN_OUT"
BAT_RUNS=3

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

assert_http_200() {
  local label="$1" method="$2" url="$3" body="${4:-}"
  local response http json
  if [[ -n "$body" ]]; then
    response="$(curl -sS -w $'\n%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body")"
  else
    response="$(curl -sS -w $'\n%{http_code}' -X "$method" "$url" -H "Authorization: Bearer $TOKEN")"
  fi
  http="${response##*$'\n'}"
  json="${response%$'\n'*}"
  if [[ "$http" != "200" ]]; then echo "[FAIL] $label (HTTP $http)" >&2; echo "$json" >&2; exit 1; fi
  printf '%s' "$json"
}

echo "============================================================"
echo " CRICPULSE RUN_OUT + 3 RUNS + NEW BATTER E2E - REPOSITORY VERIFIED"
echo "============================================================"
echo

echo "== 0. AUTHENTICATION =="
LOGIN_RESPONSE="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
LOGIN_HTTP="${LOGIN_RESPONSE##*$'\n'}"
LOGIN_JSON="${LOGIN_RESPONSE%$'\n'*}"
if [[ "$LOGIN_HTTP" != "200" ]]; then echo "[FAIL] Owner login (HTTP $LOGIN_HTTP)" >&2; echo "$LOGIN_JSON" >&2; exit 1; fi
echo "[PASS] Owner login (HTTP 200)"
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$LOGIN_JSON")"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || { echo "[FAIL] JWT accessToken missing" >&2; exit 1; }
echo "[PASS] JWT accessToken received"
assert_http_200 "Authenticated /auth/me" GET "$BASE_URL/auth/me" >/dev/null
echo "[PASS] Authenticated /auth/me (HTTP 200)"

echo
echo "== 1. READ LIVE INNINGS =="
SCORE="$(assert_http_200 "Read innings" GET "$BASE_URL/scoring/innings/$INNINGS_ID")"
MATCH_ID="$(jq -r '.matchId // .match_id // empty' <<<"$SCORE")"
STATUS="$(jq -r '.status // empty' <<<"$SCORE")"
LEGAL="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$SCORE")"
RUNS="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$SCORE")"
WICKETS="$(jq -r '.wickets // 0' <<<"$SCORE")"
STRIKER="$(jq -r '.strikerId // .striker_id // empty' <<<"$SCORE")"
NON_STRIKER="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$SCORE")"
BOWLER="$(jq -r '.currentBowlerId // .current_bowler_id // empty' <<<"$SCORE")"
echo "    innings     : $INNINGS_ID"
echo "    match       : $MATCH_ID"
echo "    status      : $STATUS"
echo "    legalBalls  : $LEGAL"
echo "    runs        : $RUNS"
echo "    wickets     : $WICKETS"
echo "    striker     : $STRIKER"
echo "    non-striker : $NON_STRIKER"
echo "    bowler      : $BOWLER"
[[ "$STATUS" == "LIVE" ]] || { echo "[FAIL] Innings is not LIVE" >&2; exit 1; }
[[ -n "$MATCH_ID" && -n "$STRIKER" && -n "$NON_STRIKER" && -n "$BOWLER" ]] || { echo "[FAIL] LIVE innings actors missing" >&2; exit 1; }
(( WICKETS < 9 )) || { echo "[FAIL] Innings already has $WICKETS wickets; cannot test new batter flow" >&2; exit 1; }
echo "[PASS] LIVE innings actors verified"

echo
echo "== 2. READ PLAYING XI =="
XI="$(assert_http_200 "Read Playing XI" GET "$BASE_URL/matches/$MATCH_ID/playing-xi")"
XI_COUNT="$(jq 'length' <<<"$XI")"
STRIKER_TEAM="$(jq -r --arg id "$STRIKER" 'first(.[] | select((.playerId // .player_id) == $id) | (.teamId // .team_id)) // empty' <<<"$XI")"
OUT_IDS_JSON="$(jq -c '[.batters[]? | select((.isOut // .is_out // false) == true) | (.playerId // .player_id) | select(. != null)]' <<<"$SCORE")"
NEW_BATTER="$(jq -r --arg team "$STRIKER_TEAM" --arg striker "$STRIKER" --arg non "$NON_STRIKER" --argjson outIds "$OUT_IDS_JSON" 'first(.[] | (.playerId // .player_id) as $id | (.teamId // .team_id) as $teamId | select($teamId == $team) | select($id != $striker and $id != $non) | select(($outIds | index($id)) == null) | $id) // empty' <<<"$XI")"
echo "    playing XI count = $XI_COUNT"
echo "    striker      : $STRIKER"
echo "    non-striker  : $NON_STRIKER"
echo "    new batter   : $NEW_BATTER"
[[ -n "$STRIKER_TEAM" ]] || { echo "[FAIL] Could not resolve batting team" >&2; exit 1; }
[[ -n "$NEW_BATTER" && "$NEW_BATTER" != "null" ]] || { echo "[FAIL] No available new batter found" >&2; exit 1; }
[[ "$NEW_BATTER" != "$STRIKER" && "$NEW_BATTER" != "$NON_STRIKER" ]] || { echo "[FAIL] Selected new batter is already active" >&2; exit 1; }
if jq -e --arg id "$NEW_BATTER" 'index($id) != null' <<<"$OUT_IDS_JSON" >/dev/null; then echo "[FAIL] Selected new batter is already dismissed" >&2; exit 1; fi
echo "[PASS] Available new batter verified"

echo
echo "== 3. RECORD RUN_OUT + 3 RUNS =="
BEFORE_LEGAL=$LEGAL
OVER_NUMBER=$((BEFORE_LEGAL / 6))
BALL_NUMBER=$((BEFORE_LEGAL % 6 + 1))
DISMISSED="$NON_STRIKER"
echo "    overNumber   : $OVER_NUMBER"
echo "    ballNumber   : $BALL_NUMBER"
echo "    striker      : $STRIKER"
echo "    non-striker  : $NON_STRIKER"
echo "    bowler       : $BOWLER"
echo "    batRuns      : $BAT_RUNS"
echo "    wicketType   : $WICKET_TYPE"
echo "    dismissed    : $DISMISSED"
echo "    new batter   : $NEW_BATTER"
DELIVERY_BODY="$(jq -nc --arg innings "$INNINGS_ID" --arg striker "$STRIKER" --arg non "$NON_STRIKER" --arg bowler "$BOWLER" --arg wicket "$WICKET_TYPE" --arg dismissed "$DISMISSED" --arg newbat "$NEW_BATTER" --argjson over "$OVER_NUMBER" --argjson ball "$BALL_NUMBER" --argjson batRuns "$BAT_RUNS" '{inningsId:$innings,overNumber:$over,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:$batRuns,extraRuns:0,extraType:null,wicketType:$wicket,dismissedPlayerId:$dismissed,newBatterId:$newbat}')"
RECORD_RESPONSE="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/scoring/innings/$INNINGS_ID/deliveries" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$DELIVERY_BODY")"
RECORD_HTTP="${RECORD_RESPONSE##*$'\n'}"
RECORD_JSON="${RECORD_RESPONSE%$'\n'*}"
if [[ "$RECORD_HTTP" != "200" ]]; then echo "[FAIL] Record RUN_OUT + 3 runs (HTTP $RECORD_HTTP)" >&2; echo "$RECORD_JSON" >&2; exit 1; fi
echo "[PASS] Record RUN_OUT + 3 runs (HTTP 200)"

echo
echo "== 4. VERIFY RUN + WICKET + STRIKE STATE =="
AFTER="$(assert_http_200 "Read innings after RUN_OUT" GET "$BASE_URL/scoring/innings/$INNINGS_ID")"
AFTER_STATUS="$(jq -r '.status // empty' <<<"$AFTER")"
AFTER_LEGAL="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$AFTER")"
AFTER_RUNS="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$AFTER")"
AFTER_WICKETS="$(jq -r '.wickets // 0' <<<"$AFTER")"
AFTER_STRIKER="$(jq -r '.strikerId // .striker_id // empty' <<<"$AFTER")"
AFTER_NON="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$AFTER")"
echo "    status      : $AFTER_STATUS"
echo "    wickets     : $AFTER_WICKETS"
echo "    legalBalls  : $AFTER_LEGAL"
echo "    runs        : $AFTER_RUNS"
echo "    striker     : $AFTER_STRIKER"
echo "    non-striker : $AFTER_NON"
[[ "$AFTER_WICKETS" -eq $((WICKETS + 1)) ]] && echo "[PASS] RUN_OUT wicket count +1" || { echo "[FAIL] Wicket count did not increment" >&2; exit 1; }
[[ "$AFTER_LEGAL" -eq $((LEGAL + 1)) ]] && echo "[PASS] Legal ball count +1" || { echo "[FAIL] Legal ball count did not increment" >&2; exit 1; }
[[ "$AFTER_RUNS" -eq $((RUNS + BAT_RUNS)) ]] && echo "[PASS] Team score increased by 3 runs" || { echo "[FAIL] Team score mismatch after RUN_OUT + 3 runs" >&2; exit 1; }
if (( AFTER_LEGAL % 6 == 0 )); then
  # Odd runs swap ends, then over completion swaps ends again.
  [[ "$AFTER_STRIKER" == "$STRIKER" ]] && echo "[PASS] Over completed: original striker correctly returns to striker end after odd-run crossing + over change" || { echo "[FAIL] Original striker position incorrect after odd-run crossing + over completion" >&2; exit 1; }
  [[ "$AFTER_NON" == "$NEW_BATTER" ]] && echo "[PASS] Over completed: new batter correctly occupies non-striker end" || { echo "[FAIL] New batter position incorrect after odd-run crossing + over completion" >&2; exit 1; }
else
  [[ "$AFTER_STRIKER" == "$NEW_BATTER" ]] && echo "[PASS] New batter correctly occupies striker end after odd-run crossing" || { echo "[FAIL] New batter position incorrect after odd-run crossing" >&2; exit 1; }
  [[ "$AFTER_NON" == "$STRIKER" ]] && echo "[PASS] Original striker correctly moved to non-striker end after odd runs" || { echo "[FAIL] Original striker position incorrect after odd runs" >&2; exit 1; }
fi


echo
echo "== 5. VERIFY BATTER / FOW / DELIVERY DATA =="
BATTER="$(jq -c --arg id "$DISMISSED" '.batters[]? | select((.playerId // .player_id) == $id)' <<<"$AFTER" | head -n1)"
DISMISSAL="$(jq -r '.dismissalType // .dismissal_type // empty' <<<"$BATTER")"
FOW_COUNT="$(jq '.fallOfWickets // .fall_of_wickets // [] | length' <<<"$AFTER")"
RECENT_WICKET="$(jq -r --arg type "$WICKET_TYPE" '.recentBalls[]? | select((.wicketType // .wicket_type) == $type) | (.wicketType // .wicket_type)' <<<"$AFTER" | head -n1)"
DISMISSED_OUT="$(jq -r '.out // .isOut // .is_out // false' <<<"$BATTER")"
[[ "$DISMISSED_OUT" == "true" ]] && echo "[PASS] Dismissed non-striker marked OUT" || { echo "[FAIL] Dismissed player not marked OUT" >&2; exit 1; }
[[ "$DISMISSAL" == "$WICKET_TYPE" ]] && echo "[PASS] Dismissed batter marked RUN_OUT" || { echo "[FAIL] Dismissal type mismatch" >&2; exit 1; }
[[ "$FOW_COUNT" -ge $((WICKETS + 1)) ]] && echo "[PASS] Fall of wicket recorded" || { echo "[FAIL] Fall of wicket not recorded" >&2; exit 1; }
[[ -n "$RECENT_WICKET" ]] && echo "[PASS] Recent delivery contains RUN_OUT wicket" || { echo "[FAIL] Recent delivery missing RUN_OUT wicket" >&2; exit 1; }

echo
echo "============================================================"
echo " RUN_OUT + 3 RUNS + NEW BATTER E2E PASSED"
echo "============================================================"
echo
echo "INNINGS_ID=$INNINGS_ID"
echo "DISMISSED_PLAYER=$DISMISSED"
echo "NEW_BATTER=$NEW_BATTER"
