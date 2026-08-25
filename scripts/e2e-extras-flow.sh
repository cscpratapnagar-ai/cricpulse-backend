#!/usr/bin/env bash
set -euo pipefail

# CricPulse EXTRAS E2E.
# Uses the real scoring API only; no direct DB writes.
#
# Required:
#   INNINGS_ID
# Optional:
#   BASE_URL (default: http://localhost:8080/api)
#   EMAIL / PASSWORD (test-user defaults below can be overridden)
#
# Coverage:
#   WIDE      -> 1 extra, illegal delivery
#   NO_BALL   -> 1 extra, illegal delivery
#   BYE       -> 1 extra, legal delivery, no bowler run
#   LEG_BYE   -> 1 extra, legal delivery, no bowler run
#
# The script reads the live innings before every delivery, so strike/current
# actors are never hard-coded between deliveries.

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-rahul.test2026@gmail.com}"
PASSWORD="${PASSWORD:-Test@12345}"
INNINGS_ID="${INNINGS_ID:-}"

if [[ -z "$INNINGS_ID" ]]; then
  echo "ERROR: INNINGS_ID is required" >&2
  echo "Usage: INNINGS_ID=<live-innings-uuid> ./scripts/e2e-extras-flow.sh" >&2
  exit 1
fi

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

request() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$body"
  else
    curl -sS -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN"
  fi
}

http_code() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -o /tmp/cricpulse-e2e-body.json -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$body"
  else
    curl -sS -o /tmp/cricpulse-e2e-body.json -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN"
  fi
}

get_score() {
  request GET "$BASE_URL/scoring/innings/$INNINGS_ID"
}

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

assert_number_delta() {
  local label="$1" before="$2" after="$3" expected="$4"
  local actual=$((after - before))
  if [[ "$actual" -ne "$expected" ]]; then
    fail "$label expected delta=$expected but got delta=$actual (before=$before after=$after)"
  fi
  echo "[PASS] $label delta=$actual"
}

record_extra() {
  local extra_type="$1"
  local extra_runs="$2"
  local expected_legal_delta="$3"

  echo
  echo "------------------------------------------------------------"
  echo "Testing $extra_type"
  echo "------------------------------------------------------------"

  local before
  before="$(get_score)"

  local status striker non_striker bowler runs legal wickets bowler_runs bowler_wides bowler_no_balls
  status="$(jq -r '.status // empty' <<<"$before")"
  striker="$(jq -r '.strikerId // .striker_id // empty' <<<"$before")"
  non_striker="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$before")"
  bowler="$(jq -r '.currentBowlerId // .current_bowler_id // empty' <<<"$before")"
  runs="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$before")"
  legal="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$before")"
  wickets="$(jq -r '.wickets // 0' <<<"$before")"

  [[ "$status" == "LIVE" ]] || fail "Innings is not LIVE before $extra_type (status=$status)"
  [[ -n "$striker" && -n "$non_striker" && -n "$bowler" ]] || fail "Live actors missing before $extra_type"

  bowler_runs="$(jq -r --arg id "$bowler" '.bowlers[]? | select((.playerId // .player_id) == $id) | (.runsConceded // .runs_conceded // 0)' <<<"$before" | head -n1)"
  bowler_wides="$(jq -r --arg id "$bowler" '.bowlers[]? | select((.playerId // .player_id) == $id) | (.wides // 0)' <<<"$before" | head -n1)"
  bowler_no_balls="$(jq -r --arg id "$bowler" '.bowlers[]? | select((.playerId // .player_id) == $id) | (.noBalls // .no_balls // 0)' <<<"$before" | head -n1)"
  bowler_runs="${bowler_runs:-0}"
  bowler_wides="${bowler_wides:-0}"
  bowler_no_balls="${bowler_no_balls:-0}"

  local body
  body="$(jq -nc \
    --arg innings "$INNINGS_ID" \
    --arg striker "$striker" \
    --arg non "$non_striker" \
    --arg bowler "$bowler" \
    --arg extra "$extra_type" \
    --argjson extraRuns "$extra_runs" \
    '{inningsId:$innings,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:0,extraRuns:$extraRuns,extraType:$extra,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"

  local code response
  code="$(http_code POST "$BASE_URL/scoring/innings/$INNINGS_ID/deliveries" "$body")"
  response="$(cat /tmp/cricpulse-e2e-body.json)"
  if [[ "$code" != "200" ]]; then
    echo "$response" >&2
    fail "$extra_type delivery rejected (HTTP $code)"
  fi
  echo "[PASS] Record $extra_type (HTTP 200)"

  local after after_runs after_legal after_wickets after_bowler_runs after_wides after_no_balls recent_type recent_extra recent_legal
  after="$(get_score)"
  after_runs="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$after")"
  after_legal="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$after")"
  after_wickets="$(jq -r '.wickets // 0' <<<"$after")"

  after_bowler_runs="$(jq -r --arg id "$bowler" '.bowlers[]? | select((.playerId // .player_id) == $id) | (.runsConceded // .runs_conceded // 0)' <<<"$after" | head -n1)"
  after_wides="$(jq -r --arg id "$bowler" '.bowlers[]? | select((.playerId // .player_id) == $id) | (.wides // 0)' <<<"$after" | head -n1)"
  after_no_balls="$(jq -r --arg id "$bowler" '.bowlers[]? | select((.playerId // .player_id) == $id) | (.noBalls // .no_balls // 0)' <<<"$after" | head -n1)"
  after_bowler_runs="${after_bowler_runs:-0}"
  after_wides="${after_wides:-0}"
  after_no_balls="${after_no_balls:-0}"

  recent_type="$(jq -r --arg type "$extra_type" '.recentBalls[]? | select((.extraType // .extra_type) == $type) | (.extraType // .extra_type)' <<<"$after" | head -n1)"
  recent_extra="$(jq -r --arg type "$extra_type" '.recentBalls[]? | select((.extraType // .extra_type) == $type) | (.extraRuns // .extra_runs // 0)' <<<"$after" | head -n1)"
  recent_legal="$(jq -r --arg type "$extra_type" '.recentBalls[]? | select((.extraType // .extra_type) == $type) | (.legalDelivery // .legal_delivery)' <<<"$after" | head -n1)"

  assert_number_delta "$extra_type total score" "$runs" "$after_runs" "$extra_runs"
  assert_number_delta "$extra_type legal-ball count" "$legal" "$after_legal" "$expected_legal_delta"
  [[ "$after_wickets" -eq "$wickets" ]] || fail "$extra_type unexpectedly changed wicket count"
  echo "[PASS] $extra_type wicket count unchanged"

  [[ "$recent_type" == "$extra_type" ]] || fail "$extra_type missing from recent deliveries"
  [[ "$recent_extra" -eq "$extra_runs" ]] || fail "$extra_type recent delivery extraRuns mismatch"
  echo "[PASS] $extra_type recent delivery recorded correctly"

  if [[ "$extra_type" == "WIDE" || "$extra_type" == "NO_BALL" ]]; then
    [[ "$recent_legal" == "false" ]] || fail "$extra_type must be an illegal delivery"
    echo "[PASS] $extra_type is illegal"
  else
    [[ "$recent_legal" == "true" ]] || fail "$extra_type must be a legal delivery"
    echo "[PASS] $extra_type is legal"
  fi

  case "$extra_type" in
    WIDE)
      assert_number_delta "Bowler runs for WIDE" "$bowler_runs" "$after_bowler_runs" "$extra_runs"
      assert_number_delta "Bowler wides" "$bowler_wides" "$after_wides" "$extra_runs"
      ;;
    NO_BALL)
      assert_number_delta "Bowler runs for NO_BALL" "$bowler_runs" "$after_bowler_runs" "$extra_runs"
      assert_number_delta "Bowler no-balls" "$bowler_no_balls" "$after_no_balls" 1
      ;;
    BYE|LEG_BYE)
      [[ "$after_bowler_runs" -eq "$bowler_runs" ]] || fail "$extra_type incorrectly increased bowler runs"
      echo "[PASS] $extra_type does not increase bowler runs"
      ;;
  esac
}

echo "============================================================"
echo " CRICPULSE EXTRAS E2E - WIDE / NO_BALL / BYE / LEG_BYE"
echo "============================================================"
echo

echo "== 0. AUTHENTICATION =="
LOGIN_CODE="$(curl -sS -o /tmp/cricpulse-login.json -w '%{http_code}' -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
LOGIN_JSON="$(cat /tmp/cricpulse-login.json)"
[[ "$LOGIN_CODE" == "200" ]] || { echo "$LOGIN_JSON" >&2; fail "Login failed (HTTP $LOGIN_CODE)"; }
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$LOGIN_JSON")"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || fail "JWT accessToken missing"
echo "[PASS] Owner login (HTTP 200)"

ME_CODE="$(http_code GET "$BASE_URL/auth/me")"
[[ "$ME_CODE" == "200" ]] || { cat /tmp/cricpulse-e2e-body.json >&2; fail "/auth/me failed (HTTP $ME_CODE)"; }
echo "[PASS] Authenticated /auth/me (HTTP 200)"

echo
echo "== 1. VERIFY LIVE INNINGS =="
INITIAL="$(get_score)"
INITIAL_STATUS="$(jq -r '.status // empty' <<<"$INITIAL")"
INITIAL_RUNS="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$INITIAL")"
INITIAL_LEGAL="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$INITIAL")"
INITIAL_WICKETS="$(jq -r '.wickets // 0' <<<"$INITIAL")"
INITIAL_STRIKER="$(jq -r '.strikerId // .striker_id // empty' <<<"$INITIAL")"
INITIAL_NON="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$INITIAL")"
INITIAL_BOWLER="$(jq -r '.currentBowlerId // .current_bowler_id // empty' <<<"$INITIAL")"

echo "    innings     : $INNINGS_ID"
echo "    status      : $INITIAL_STATUS"
echo "    runs        : $INITIAL_RUNS"
echo "    legalBalls  : $INITIAL_LEGAL"
echo "    wickets     : $INITIAL_WICKETS"
echo "    striker     : $INITIAL_STRIKER"
echo "    non-striker : $INITIAL_NON"
echo "    bowler      : $INITIAL_BOWLER"
[[ "$INITIAL_STATUS" == "LIVE" ]] || fail "Innings must be LIVE"
[[ -n "$INITIAL_STRIKER" && -n "$INITIAL_NON" && -n "$INITIAL_BOWLER" ]] || fail "LIVE innings actors missing"
echo "[PASS] LIVE innings verified"

echo
echo "== 2. EXTRAS COVERAGE =="
record_extra "WIDE" 1 0
record_extra "NO_BALL" 1 0
record_extra "BYE" 1 1
record_extra "LEG_BYE" 1 1

echo
echo "== 3. FINAL STATE =="
FINAL="$(get_score)"
FINAL_RUNS="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$FINAL")"
FINAL_LEGAL="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$FINAL")"
FINAL_WICKETS="$(jq -r '.wickets // 0' <<<"$FINAL")"
EXPECTED_RUNS=$((INITIAL_RUNS + 4))
EXPECTED_LEGAL=$((INITIAL_LEGAL + 2))
[[ "$FINAL_RUNS" -eq "$EXPECTED_RUNS" ]] || fail "Final runs expected=$EXPECTED_RUNS actual=$FINAL_RUNS"
[[ "$FINAL_LEGAL" -eq "$EXPECTED_LEGAL" ]] || fail "Final legal balls expected=$EXPECTED_LEGAL actual=$FINAL_LEGAL"
[[ "$FINAL_WICKETS" -eq "$INITIAL_WICKETS" ]] || fail "Final wicket count changed unexpectedly"

echo "[PASS] Final total runs = $FINAL_RUNS"
echo "[PASS] Final legal balls = $FINAL_LEGAL"
echo "[PASS] Final wickets unchanged = $FINAL_WICKETS"

echo
echo "============================================================"
echo " EXTRAS E2E PASSED"
echo "============================================================"
echo "INNINGS_ID=$INNINGS_ID"
echo "Coverage=WIDE,NO_BALL,BYE,LEG_BYE"
