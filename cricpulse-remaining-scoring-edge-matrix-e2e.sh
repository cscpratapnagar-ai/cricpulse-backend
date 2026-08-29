#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-rahul.test2026@gmail.com}"
PASSWORD="${PASSWORD:-Test@12345}"
INNINGS_ID="${INNINGS_ID:?ERROR: Set INNINGS_ID to the current LIVE innings UUID}"

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

post_delivery() {
  local body="$1" response http json
  response="$(curl -sS -w $'\n%{http_code}' -X POST \
    "$BASE_URL/scoring/innings/$INNINGS_ID/deliveries" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$body")"
  http="${response##*$'\n'}"
  json="${response%$'\n'*}"
  [[ "$http" == "200" ]] || { echo "[FAIL] Delivery request (HTTP $http)" >&2; echo "$json" >&2; exit 1; }
  printf '%s' "$json"
}

login="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
login_http="${login##*$'\n'}"
login_json="${login%$'\n'*}"
[[ "$login_http" == "200" ]] || { echo "[FAIL] Owner login (HTTP $login_http)" >&2; echo "$login_json" >&2; exit 1; }
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$login_json")"
[[ -n "$TOKEN" ]] || { echo "[FAIL] JWT accessToken missing" >&2; exit 1; }

echo "============================================================"
echo " CRICPULSE REMAINING SCORING EDGE MATRIX E2E"
echo "============================================================"
echo "[PASS] Authentication"

for case_name in "WIDE" "NO_BALL" "BYE" "LEG_BYE" "NORMAL_RUN" "DOT"; do
  echo
  echo "------------------------------------------------------------"
  echo " CASE: $case_name"
  echo "------------------------------------------------------------"

  before="$(get_json "$BASE_URL/scoring/innings/$INNINGS_ID")"
  status="$(jq -r '.status // empty' <<<"$before")"
  match="$(jq -r '.matchId // .match_id // empty' <<<"$before")"
  legal="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$before")"
  runs="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$before")"
  striker="$(jq -r '.strikerId // .striker_id // empty' <<<"$before")"
  non="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$before")"
  bowler="$(jq -r '.currentBowlerId // .current_bowler_id // empty' <<<"$before")"

  [[ "$status" == "LIVE" ]] || { echo "[FAIL] Innings is not LIVE" >&2; exit 1; }
  [[ -n "$striker" && -n "$non" && -n "$bowler" ]] || { echo "[FAIL] Missing active actors" >&2; exit 1; }
  echo "    innings=$INNINGS_ID match=$match legalBalls=$legal runs=$runs"
  echo "    striker=$striker non-striker=$non bowler=$bowler"

  over=$((legal / 6))
  ball=$((legal % 6 + 1))
  case "$case_name" in
    WIDE)
      bat=0; extra=1; extra_type="WIDE"; expected_legal=$legal; expected_runs=$((runs+1));;
    NO_BALL)
      bat=0; extra=1; extra_type="NO_BALL"; expected_legal=$legal; expected_runs=$((runs+1));;
    BYE)
      bat=0; extra=1; extra_type="BYE"; expected_legal=$((legal+1)); expected_runs=$((runs+1));;
    LEG_BYE)
      bat=0; extra=1; extra_type="LEG_BYE"; expected_legal=$((legal+1)); expected_runs=$((runs+1));;
    NORMAL_RUN)
      bat=1; extra=0; extra_type="null"; expected_legal=$((legal+1)); expected_runs=$((runs+1));;
    DOT)
      bat=0; extra=0; extra_type="null"; expected_legal=$((legal+1)); expected_runs=$runs;;
  esac

  body="$(jq -nc --arg innings "$INNINGS_ID" --arg striker "$striker" --arg non "$non" --arg bowler "$bowler" --arg extraType "$extra_type" --argjson over "$over" --argjson ball "$ball" --argjson bat "$bat" --argjson extra "$extra" '{inningsId:$innings,overNumber:$over,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:$bat,extraRuns:$extra,extraType:(if $extraType=="null" then null else $extraType end),wicketType:null,dismissedPlayerId:null,newBatterId:null}')"
  post_delivery "$body" >/dev/null
  echo "[PASS] $case_name recorded"

  after="$(get_json "$BASE_URL/scoring/innings/$INNINGS_ID")"
  after_legal="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$after")"
  after_runs="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$after")"
  [[ "$after_legal" -eq "$expected_legal" ]] || { echo "[FAIL] $case_name legal ball state mismatch" >&2; exit 1; }
  [[ "$after_runs" -eq "$expected_runs" ]] || { echo "[FAIL] $case_name score state mismatch" >&2; exit 1; }
  echo "[PASS] $case_name state verified (legalBalls=$after_legal runs=$after_runs)"
done

echo

echo "============================================================"
echo " REMAINING SCORING EDGE MATRIX E2E PASSED"
echo " Cases passed: WIDE, NO_BALL, BYE, LEG_BYE, NORMAL_RUN, DOT"
echo "============================================================"
echo "INNINGS_ID=$INNINGS_ID"
