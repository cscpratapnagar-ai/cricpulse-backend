#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-rahul.test2026@gmail.com}"
PASSWORD="${PASSWORD:-Test@12345}"
INNINGS_ID="${INNINGS_ID:?ERROR: Set INNINGS_ID to the current LIVE innings UUID}"

WICKET_TYPE="RUN_OUT"
BAT_RUNS=0

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

get_json() {
  local response http body
  response="$(curl -sS -w $'\n%{http_code}' -H "Authorization: Bearer $TOKEN" "$1")"
  http="${response##*$'\n'}"
  body="${response%$'\n'*}"
  [[ "$http" == "200" ]] || { echo "[FAIL] GET $1 (HTTP $http)" >&2; echo "$body" >&2; exit 1; }
  printf '%s' "$body"
}

echo "============================================================"
echo " CRICPULSE RUN_OUT STRIKER + 0 RUNS + NEW BATTER E2E"
echo "============================================================"
echo

echo "== 0. AUTHENTICATION =="
LOGIN="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
HTTP="${LOGIN##*$'\n'}"; JSON="${LOGIN%$'\n'*}"
[[ "$HTTP" == "200" ]] || { echo "[FAIL] Owner login (HTTP $HTTP)" >&2; echo "$JSON" >&2; exit 1; }
echo "[PASS] Owner login (HTTP 200)"
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$JSON")"
[[ -n "$TOKEN" ]] || { echo "[FAIL] JWT accessToken missing" >&2; exit 1; }
echo "[PASS] JWT accessToken received"
get_json "$BASE_URL/auth/me" >/dev/null
echo "[PASS] Authenticated /auth/me (HTTP 200)"

echo
echo "== 1. READ LIVE INNINGS =="
BEFORE="$(get_json "$BASE_URL/scoring/innings/$INNINGS_ID")"
MATCH_ID="$(jq -r '.matchId // .match_id // empty' <<<"$BEFORE")"
STATUS="$(jq -r '.status // empty' <<<"$BEFORE")"
LEGAL="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$BEFORE")"
RUNS="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$BEFORE")"
WICKETS="$(jq -r '.wickets // 0' <<<"$BEFORE")"
STRIKER="$(jq -r '.strikerId // .striker_id // empty' <<<"$BEFORE")"
NON_STRIKER="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$BEFORE")"
BOWLER="$(jq -r '.currentBowlerId // .current_bowler_id // empty' <<<"$BEFORE")"
echo "    innings     : $INNINGS_ID"
echo "    status      : $STATUS"
echo "    legalBalls  : $LEGAL"
echo "    runs        : $RUNS"
echo "    wickets     : $WICKETS"
echo "    striker     : $STRIKER"
echo "    non-striker : $NON_STRIKER"
echo "    bowler      : $BOWLER"
[[ "$STATUS" == "LIVE" ]] || { echo "[FAIL] Innings is not LIVE" >&2; exit 1; }
[[ -n "$MATCH_ID" && -n "$STRIKER" && -n "$NON_STRIKER" && -n "$BOWLER" ]] || { echo "[FAIL] LIVE innings actors missing" >&2; exit 1; }
(( WICKETS < 9 )) || { echo "[FAIL] Too many wickets for a new-batter test" >&2; exit 1; }
echo "[PASS] LIVE innings actors verified"

echo
echo "== 2. READ PLAYING XI / FIND NEW BATTER =="
XI="$(get_json "$BASE_URL/matches/$MATCH_ID/playing-xi")"
STRIKER_TEAM="$(jq -r --arg id "$STRIKER" 'first(.[] | select((.playerId // .player_id) == $id) | (.teamId // .team_id)) // empty' <<<"$XI")"
OUT_IDS="$(jq -c '[.batters[]? | select((.isOut // .is_out // .out // false) == true) | (.playerId // .player_id) | select(. != null)]' <<<"$BEFORE")"
NEW_BATTER="$(jq -r --arg team "$STRIKER_TEAM" --arg striker "$STRIKER" --arg non "$NON_STRIKER" --argjson outIds "$OUT_IDS" 'first(.[] | (.playerId // .player_id) as $id | (.teamId // .team_id) as $team | select($team == $team) | select(($team == $ARGS.named.team)) | select($id != $striker and $id != $non) | select(($outIds | index($id)) == null) | $id)' --arg team "$STRIKER_TEAM" <<<"$XI")"
# Fallback with straightforward jq expression because the first expression intentionally avoids relying on field ordering.
NEW_BATTER="$(jq -r --arg team "$STRIKER_TEAM" --arg striker "$STRIKER" --arg non "$NON_STRIKER" --argjson outIds "$OUT_IDS" 'first(.[] | (.playerId // .player_id) as $id | (.teamId // .team_id) as $teamId | select($teamId == $team) | select($id != $striker and $id != $non) | select(($outIds | index($id)) == null) | $id) // empty' <<<"$XI")"

echo "    new batter   : $NEW_BATTER"
[[ -n "$STRIKER_TEAM" ]] || { echo "[FAIL] Could not resolve batting team" >&2; exit 1; }
[[ -n "$NEW_BATTER" ]] || { echo "[FAIL] No available new batter found" >&2; exit 1; }
echo "[PASS] Available new batter verified"

echo
echo "== 3. RECORD RUN_OUT ON STRIKER + 0 RUNS =="
OVER_NUMBER=$((LEGAL / 6))
BALL_NUMBER=$((LEGAL % 6 + 1))
DISMISSED="$STRIKER"
echo "    overNumber   : $OVER_NUMBER"
echo "    ballNumber   : $BALL_NUMBER"
echo "    striker      : $STRIKER"
echo "    non-striker  : $NON_STRIKER"
echo "    bowler       : $BOWLER"
echo "    batRuns      : $BAT_RUNS"
echo "    wicketType   : $WICKET_TYPE"
echo "    dismissed    : $DISMISSED"
echo "    new batter   : $NEW_BATTER"
BODY="$(jq -nc --arg innings "$INNINGS_ID" --arg striker "$STRIKER" --arg non "$NON_STRIKER" --arg bowler "$BOWLER" --arg wicket "$WICKET_TYPE" --arg dismissed "$DISMISSED" --arg newbat "$NEW_BATTER" --argjson over "$OVER_NUMBER" --argjson ball "$BALL_NUMBER" --argjson batRuns "$BAT_RUNS" '{inningsId:$innings,overNumber:$over,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:$batRuns,extraRuns:0,extraType:null,wicketType:$wicket,dismissedPlayerId:$dismissed,newBatterId:$newbat}')"
RESPONSE="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/scoring/innings/$INNINGS_ID/deliveries" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$BODY")"
HTTP="${RESPONSE##*$'\n'}"; JSON="${RESPONSE%$'\n'*}"
[[ "$HTTP" == "200" ]] || { echo "[FAIL] Record RUN_OUT striker + 0 runs (HTTP $HTTP)" >&2; echo "$JSON" >&2; exit 1; }
echo "[PASS] Record RUN_OUT striker + 0 runs (HTTP 200)"

echo
echo "== 4. VERIFY WICKET / SCORE / STRIKE STATE =="
AFTER="$(get_json "$BASE_URL/scoring/innings/$INNINGS_ID")"
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
[[ "$AFTER_WICKETS" -eq $((WICKETS + 1)) ]] && echo "[PASS] Wicket count +1" || { echo "[FAIL] Wicket count mismatch" >&2; exit 1; }
[[ "$AFTER_LEGAL" -eq $((LEGAL + 1)) ]] && echo "[PASS] Legal ball count +1" || { echo "[FAIL] Legal ball count mismatch" >&2; exit 1; }
[[ "$AFTER_RUNS" -eq "$RUNS" ]] && echo "[PASS] Score unchanged at 0 additional runs" || { echo "[FAIL] Score changed unexpectedly" >&2; exit 1; }
[[ "$AFTER_STRIKER" == "$NEW_BATTER" ]] && echo "[PASS] New batter correctly replaces dismissed striker at striker end" || { echo "[FAIL] New batter is not striker after striker dismissal" >&2; exit 1; }
[[ "$AFTER_NON" == "$NON_STRIKER" ]] && echo "[PASS] Original non-striker remains at non-striker end" || { echo "[FAIL] Non-striker position changed unexpectedly" >&2; exit 1; }

echo
echo "== 5. VERIFY BATTER / FOW / DELIVERY DATA =="
BATTER="$(jq -c --arg id "$DISMISSED" '.batters[]? | select((.playerId // .player_id) == $id)' <<<"$AFTER" | head -n1)"
OUT="$(jq -r '.out // .isOut // .is_out // false' <<<"$BATTER")"
DISMISSAL="$(jq -r '.dismissalType // .dismissal_type // empty' <<<"$BATTER")"
FOW_COUNT="$(jq '.fallOfWickets // .fall_of_wickets // [] | length' <<<"$AFTER")"
RECENT="$(jq -r --arg type "$WICKET_TYPE" '.recentBalls[]? | select((.wicketType // .wicket_type) == $type) | (.wicketType // .wicket_type)' <<<"$AFTER" | head -n1)"
[[ "$OUT" == "true" ]] && echo "[PASS] Dismissed striker marked OUT" || { echo "[FAIL] Dismissed striker not marked OUT" >&2; exit 1; }
[[ "$DISMISSAL" == "$WICKET_TYPE" ]] && echo "[PASS] Dismissal type RUN_OUT" || { echo "[FAIL] Dismissal type mismatch" >&2; exit 1; }
[[ "$FOW_COUNT" -ge $((WICKETS + 1)) ]] && echo "[PASS] Fall of wicket recorded" || { echo "[FAIL] Fall of wicket not recorded" >&2; exit 1; }
[[ -n "$RECENT" ]] && echo "[PASS] Recent delivery contains RUN_OUT" || { echo "[FAIL] Recent delivery missing RUN_OUT" >&2; exit 1; }

echo
echo "============================================================"
echo " RUN_OUT STRIKER + 0 RUNS + NEW BATTER E2E PASSED"
echo "============================================================"
echo "INNINGS_ID=$INNINGS_ID"
echo "DISMISSED_PLAYER=$DISMISSED"
echo "NEW_BATTER=$NEW_BATTER"
