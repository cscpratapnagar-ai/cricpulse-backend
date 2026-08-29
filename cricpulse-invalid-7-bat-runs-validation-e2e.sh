#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-rahul.test2026@gmail.com}"
PASSWORD="${PASSWORD:-Test@12345}"
INNINGS_ID="${INNINGS_ID:-${INNINGS_ID:?ERROR: Set INNINGS_ID to the current LIVE innings UUID}}"

BAT_RUNS=7
WICKET_TYPE="RUN_OUT"

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

get200() {
  local response http body
  response="$(curl -sS -w $'\n%{http_code}' -H "Authorization: Bearer $TOKEN" "$1")"
  http="${response##*$'\n'}"; body="${response%$'\n'*}"
  [[ "$http" == "200" ]] || { echo "[FAIL] GET $1 (HTTP $http)"; echo "$body"; exit 1; }
  printf '%s' "$body"
}

echo "============================================================"
echo " CRICPULSE INVALID BAT RUNS (7) VALIDATION E2E"
echo "============================================================"

echo
echo "== 0. AUTHENTICATION =="
LOGIN="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
HTTP="${LOGIN##*$'\n'}"; JSON="${LOGIN%$'\n'*}"
[[ "$HTTP" == "200" ]] || { echo "[FAIL] Owner login (HTTP $HTTP)"; echo "$JSON"; exit 1; }
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$JSON")"
[[ -n "$TOKEN" ]] || { echo "[FAIL] JWT missing"; exit 1; }
echo "[PASS] Owner login + JWT"

echo
echo "== 1. READ LIVE INNINGS =="
BEFORE="$(get200 "$BASE_URL/scoring/innings/$INNINGS_ID")"
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
[[ "$STATUS" == "LIVE" && -n "$STRIKER" && -n "$NON_STRIKER" && -n "$BOWLER" ]] || { echo "[FAIL] LIVE innings actors missing"; exit 1; }
echo "[PASS] LIVE innings verified"

OVER_NUMBER=$((LEGAL / 6))
BALL_NUMBER=$((LEGAL % 6 + 1))
BODY="$(jq -nc --arg innings "$INNINGS_ID" --arg striker "$STRIKER" --arg non "$NON_STRIKER" --arg bowler "$BOWLER" --arg wicket "$WICKET_TYPE" --arg dismissed "$NON_STRIKER" --argjson over "$OVER_NUMBER" --argjson ball "$BALL_NUMBER" --argjson batRuns "$BAT_RUNS" '{inningsId:$innings,overNumber:$over,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:$batRuns,extraRuns:0,extraType:null,wicketType:$wicket,dismissedPlayerId:$dismissed,newBatterId:null}')"

echo
echo "== 2. SUBMIT INVALID 7 BAT RUNS =="
RESPONSE="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/scoring/innings/$INNINGS_ID/deliveries" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$BODY")"
HTTP="${RESPONSE##*$'\n'}"; JSON="${RESPONSE%$'\n'*}"
[[ "$HTTP" == "400" ]] || { echo "[FAIL] Expected HTTP 400, got $HTTP"; echo "$JSON"; exit 1; }
echo "[PASS] Invalid 7 bat runs rejected (HTTP 400)"
CODE="$(jq -r '.code // empty' <<<"$JSON")"
MESSAGE="$(jq -r '.message // empty' <<<"$JSON")"
[[ "$CODE" == "VALIDATION_ERROR" ]] || { echo "[FAIL] Expected VALIDATION_ERROR, got $CODE"; exit 1; }
[[ "$MESSAGE" == *"between 0 and 6"* ]] || { echo "[FAIL] Unexpected validation message: $MESSAGE"; exit 1; }
echo "[PASS] Validation error contract verified"

echo
echo "== 3. VERIFY NO STATE MUTATION =="
AFTER="$(get200 "$BASE_URL/scoring/innings/$INNINGS_ID")"
AFTER_LEGAL="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$AFTER")"
AFTER_RUNS="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$AFTER")"
AFTER_WICKETS="$(jq -r '.wickets // 0' <<<"$AFTER")"
[[ "$AFTER_LEGAL" == "$LEGAL" ]] || { echo "[FAIL] Invalid request changed legal balls"; exit 1; }
[[ "$AFTER_RUNS" == "$RUNS" ]] || { echo "[FAIL] Invalid request changed score"; exit 1; }
[[ "$AFTER_WICKETS" == "$WICKETS" ]] || { echo "[FAIL] Invalid request changed wickets"; exit 1; }
echo "[PASS] No innings state mutated"

echo
echo "============================================================"
echo " INVALID 7 BAT RUNS VALIDATION E2E PASSED"
echo "============================================================"
echo "INNINGS_ID=$INNINGS_ID"
