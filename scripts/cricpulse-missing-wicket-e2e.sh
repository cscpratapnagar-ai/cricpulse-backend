#!/usr/bin/env bash
set -euo pipefail

# CricPulse missing-wicket E2E.
# Uses the SAME HTTP login/scoring flow as the existing wicket E2E tests.
# Credentials are intentionally not stored in the repository.
#
# Required environment variables:
#   EMAIL PASSWORD INNINGS_ID
# Optional:
#   BASE_URL (default: http://localhost:8080/api)
#   WICKET_TYPE (default: OBSTRUCTING_THE_FIELD)
#
# Supported missing wicket types:
#   HIT_BALL_TWICE, OBSTRUCTING_THE_FIELD, TIMED_OUT, RETIRED_HURT

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
INNINGS_ID="${INNINGS_ID:-}"
WICKET_TYPE="${WICKET_TYPE:-OBSTRUCTING_THE_FIELD}"

if [[ -z "$EMAIL" || -z "$PASSWORD" || -z "$INNINGS_ID" ]]; then
  echo "ERROR: EMAIL, PASSWORD and INNINGS_ID are required" >&2
  exit 1
fi

case "$WICKET_TYPE" in
  HIT_BALL_TWICE|OBSTRUCTING_THE_FIELD|TIMED_OUT|RETIRED_HURT) ;;
  *)
    echo "ERROR: unsupported test wicket type: $WICKET_TYPE" >&2
    exit 1
    ;;
esac

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

assert_http_200() {
  local label="$1" method="$2" url="$3" body="${4:-}"
  local response http
  if [[ -n "$body" ]]; then
    response="$(curl -sS -w $'\n%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' -d "$body")"
  else
    response="$(curl -sS -w $'\n%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN")"
  fi
  http="${response##*$'\n'}"
  response="${response%$'\n'*}"
  if [[ "$http" != "200" ]]; then
    echo "[FAIL] $label (HTTP $http)" >&2
    echo "$response" >&2
    exit 1
  fi
  printf '%s' "$response"
}

echo "============================================================"
echo " CRICPULSE $WICKET_TYPE + NEW BATTER E2E - REPOSITORY VERIFIED"
echo "============================================================"
echo

echo "== 0. AUTHENTICATION =="
LOGIN_RESPONSE="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
LOGIN_HTTP="${LOGIN_RESPONSE##*$'\n'}"
LOGIN_JSON="${LOGIN_RESPONSE%$'\n'*}"
if [[ "$LOGIN_HTTP" != "200" ]]; then
  echo "[FAIL] Owner login (HTTP $LOGIN_HTTP)" >&2
  echo "$LOGIN_JSON" >&2
  exit 1
fi
echo "[PASS] Owner login (HTTP 200)"
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$LOGIN_JSON")"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || { echo "[FAIL] JWT accessToken missing" >&2; exit 1; }
echo "[PASS] JWT accessToken received"
ME="$(assert_http_200 "Authenticated /auth/me" GET "$BASE_URL/auth/me")"
echo "[PASS] Authenticated /auth/me (HTTP 200)"

echo
echo "== 1. READ LIVE INNINGS =="
SCORE="$(assert_http_200 "Read innings" GET "$BASE_URL/scoring/innings/$INNINGS_ID")"
STATUS="$(jq -r '.status // empty' <<<"$SCORE")"
LEGAL="$(jq -r '.legalBalls // .legal_balls // 0' <<<"$SCORE")"
RUNS="$(jq -r '.runs // .totalRuns // .total_runs // 0' <<<"$SCORE")"
WICKETS="$(jq -r '.wickets // 0' <<<"$SCORE")"
STRIKER="$(jq -r '.strikerId // .striker_id // empty' <<<"$SCORE")"
NON_STRIKER="$(jq -r '.nonStrikerId // .non_striker_id // empty' <<<"$SCORE")"
BOWLER="$(jq -r '.currentBowlerId // .current_bowler_id // empty' <<<"$SCORE")"
echo "    innings     : $INNINGS_ID"
echo "    status      : $STATUS"
echo "    legalBalls  : $LEGAL"
echo "    runs        : $RUNS"
echo "    wickets     : $WICKETS"
echo "    striker     : $STRIKER"
echo "    non-striker : $NON_STRIKER"
echo "    bowler      : $BOWLER"
[[ "$STATUS" == "LIVE" ]] || { echo "[FAIL] Innings is not LIVE" >&2; exit 1; }
[[ -n "$STRIKER" && -n "$NON_STRIKER" && -n "$BOWLER" ]] || { echo "[FAIL] LIVE innings actors missing" >&2; exit 1; }
echo "[PASS] LIVE innings actors verified"

if (( WICKETS >= 9 )); then
  echo "[FAIL] This innings has $WICKETS wickets; a new batter is required for this E2E." >&2
  exit 1
fi

echo
echo "== 2. READ PLAYING XI =="
XI="$(assert_http_200 "Read Playing XI" GET "$BASE_URL/scoring/innings/$INNINGS_ID/playing-xi")"
XI_COUNT="$(jq 'length' <<<"$XI")"
STRIKER_TEAM="$(jq -r --arg id "$STRIKER" '.[] | select((.playerId // .player_id) == $id) | (.teamId // .team_id) // empty' <<<"$XI" | head -n1)"
NEW_BATTER="$(jq -r --arg team "$STRIKER_TEAM" --arg striker "$STRIKER" --arg non "$NON_STRIKER" '.[] | select((.teamId // .team_id) == $team) | (.playerId // .player_id) | select(. != $striker and . != $non)' <<<"$XI" | head -n1)"
echo "    playing XI count = $XI_COUNT"
echo "    striker      : $STRIKER"
echo "    non-striker  : $NON_STRIKER"
echo "    new batter   : $NEW_BATTER"
[[ -n "$STRIKER_TEAM" ]] || { echo "[FAIL] Could not resolve striker team from Playing XI" >&2; exit 1; }
[[ -n "$NEW_BATTER" ]] || { echo "[FAIL] No available new batter found" >&2; exit 1; }
echo "[PASS] Available new batter verified"

echo
echo "== 3. RECORD $WICKET_TYPE WICKET =="
BEFORE_LEGAL=$LEGAL
OVER_NUMBER=$((BEFORE_LEGAL / 6))
BALL_NUMBER=$((BEFORE_LEGAL % 6 + 1))
echo "    overNumber   : $OVER_NUMBER"
echo "    ballNumber   : $BALL_NUMBER"
echo "    striker      : $STRIKER"
echo "    non-striker  : $NON_STRIKER"
echo "    bowler       : $BOWLER"
echo "    wicketType   : $WICKET_TYPE"
echo "    dismissed    : $STRIKER"
echo "    new batter   : $NEW_BATTER"

DELIVERY_BODY="$(jq -nc \
  --arg innings "$INNINGS_ID" \
  --arg striker "$STRIKER" \
  --arg non "$NON_STRIKER" \
  --arg bowler "$BOWLER" \
  --arg wicket "$WICKET_TYPE" \
  --arg dismissed "$STRIKER" \
  --arg newbat "$NEW_BATTER" \
  '{inningsId:$innings,overNumber:'"$OVER_NUMBER"',ballNumber:'"$BALL_NUMBER"',strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:0,extraRuns:0,extraType:null,wicketType:$wicket,dismissedPlayerId:$dismissed,newBatterId:$newbat}')"

RECORD_RESPONSE="$(curl -sS -w $'\n%{http_code}' -X POST "$BASE_URL/scoring/innings/$INNINGS_ID/deliveries" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$DELIVERY_BODY")"
RECORD_HTTP="${RECORD_RESPONSE##*$'\n'}"
RECORD_JSON="${RECORD_RESPONSE%$'\n'*}"
if [[ "$RECORD_HTTP" != "200" ]]; then
  echo "[FAIL] Record $WICKET_TYPE wicket (HTTP $RECORD_HTTP)" >&2
  echo "$RECORD_JSON" >&2
  exit 1
fi
echo "[PASS] Record $WICKET_TYPE wicket (HTTP 200)"

echo
echo "== 4. VERIFY WICKET STATE =="
AFTER="$(assert_http_200 "Read innings after wicket" GET "$BASE_URL/scoring/innings/$INNINGS_ID")"
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
[[ "$AFTER_WICKETS" -eq $((WICKETS + 1)) ]] && echo "[PASS] $WICKET_TYPE wicket count +1" || { echo "[FAIL] Wicket count did not increment" >&2; exit 1; }
[[ "$AFTER_LEGAL" -eq $((LEGAL + 1)) ]] && echo "[PASS] Legal ball count +1" || { echo "[FAIL] Legal ball count did not increment" >&2; exit 1; }
[[ "$AFTER_RUNS" -eq "$RUNS" ]] && echo "[PASS] Score unchanged for wicket dot" || { echo "[FAIL] Score changed unexpectedly" >&2; exit 1; }
if [[ "$AFTER_STRIKER" == "$NEW_BATTER" || "$AFTER_NON" == "$NEW_BATTER" ]]; then
  echo "[PASS] New batter entered active batting pair"
else
  echo "[FAIL] New batter is not in active batting pair" >&2
  exit 1
fi

echo
echo "== 5. VERIFY BATTER / FOW / BOWLER DATA =="
BATTER="$(jq -c --arg id "$STRIKER" '.batters[]? | select(.playerId == $id)' <<<"$AFTER" | head -n1)"
FOW_COUNT="$(jq '.fallOfWickets // [] | length' <<<"$AFTER")"
RECENT_WICKET="$(jq -r --arg type "$WICKET_TYPE" '.recentBalls[]? | select(.wicketType == $type) | .wicketType' <<<"$AFTER" | head -n1)"
[[ "$(jq -r '.dismissalType // empty' <<<"$BATTER")" == "$WICKET_TYPE" ]] && echo "[PASS] Dismissed batter marked $WICKET_TYPE" || { echo "[FAIL] Dismissed batter dismissal type mismatch" >&2; exit 1; }
[[ "$FOW_COUNT" -ge $((WICKETS + 1)) ]] && echo "[PASS] Fall of wicket recorded" || { echo "[FAIL] Fall of wicket not recorded" >&2; exit 1; }
[[ -n "$RECENT_WICKET" ]] && echo "[PASS] Recent delivery contains $WICKET_TYPE wicket" || { echo "[FAIL] Recent delivery missing $WICKET_TYPE" >&2; exit 1; }

echo
echo "============================================================"
echo " $WICKET_TYPE + NEW BATTER E2E PASSED"
echo "============================================================"
echo
echo "INNINGS_ID=$INNINGS_ID"
echo "DISMISSED_PLAYER=$STRIKER"
echo "NEW_BATTER=$NEW_BATTER"
