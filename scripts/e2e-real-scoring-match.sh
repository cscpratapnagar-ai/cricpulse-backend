#!/usr/bin/env bash
set -euo pipefail

# Real scoring E2E regression for the normal two-innings match lifecycle.
# This intentionally uses the existing HTTP APIs; it never writes match,
# innings, delivery, result, or tournament rows directly to PostgreSQL.
#
# Required:
#   EMAIL PASSWORD TEAM_A_ID TEAM_B_ID
#   A_STRIKER_ID A_NON_STRIKER_ID B_BOWLER_ID
#   B_STRIKER_ID B_NON_STRIKER_ID A_BOWLER_ID
#
# Optional:
#   MATCH_ID - use an existing disposable/unplayed fixture instead of creating one.
#   TOURNAMENT_ID - when supplied, the completed match must be represented in
#   the tournament points table. When omitted, tournament verification is skipped.
#   MATCH_NAME - name used when a disposable fixture is created.
#   BASE_URL (default http://localhost:8080/api)

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
MATCH_ID="${MATCH_ID:-}"
TEAM_A_ID="${TEAM_A_ID:-}"
TEAM_B_ID="${TEAM_B_ID:-}"
A_STRIKER_ID="${A_STRIKER_ID:-}"
A_NON_STRIKER_ID="${A_NON_STRIKER_ID:-}"
B_BOWLER_ID="${B_BOWLER_ID:-}"
B_STRIKER_ID="${B_STRIKER_ID:-}"
B_NON_STRIKER_ID="${B_NON_STRIKER_ID:-}"
A_BOWLER_ID="${A_BOWLER_ID:-}"
TOURNAMENT_ID="${TOURNAMENT_ID:-}"
MATCH_NAME="${MATCH_NAME:-CricPulse Lifecycle E2E $(date -u +%Y%m%d-%H%M%S)}"

required=(EMAIL PASSWORD TEAM_A_ID TEAM_B_ID A_STRIKER_ID A_NON_STRIKER_ID B_BOWLER_ID B_STRIKER_ID B_NON_STRIKER_ID A_BOWLER_ID)
for name in "${required[@]}"; do
  if [[ -z "${!name}" ]]; then
    echo "ERROR: $name is required" >&2
    exit 1
  fi
done

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

request() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -f -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$body"
  else
    curl -sS -f -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN"
  fi
}

request_status() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -o "$TMP_DIR/rejection-body" -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$body"
  else
    curl -sS -o "$TMP_DIR/rejection-body" -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN"
  fi
}

expect_rejection() {
  local label="$1" method="$2" url="$3" body="${4:-}"
  local status
  status="$(request_status "$method" "$url" "$body")"
  if [[ "$status" -lt 400 || "$status" -ge 500 ]]; then
    echo "FAIL: $label expected 4xx, received HTTP $status" >&2
    cat "$TMP_DIR/rejection-body" >&2 || true
    exit 1
  fi
  echo "PASS: $label (HTTP $status)"
}

fail_with_body() {
  local label="$1" method="$2" url="$3" body="${4:-}"
  echo "ERROR: $label" >&2
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$body" >&2 || true
  else
    curl -sS -X "$method" "$url" -H "Authorization: Bearer $TOKEN" >&2 || true
  fi
  echo >&2
  exit 1
}

echo "[1/10] Login"
LOGIN_RESPONSE="$(curl -sS -f -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$LOGIN_RESPONSE")"
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "ERROR: login succeeded but no access token was found" >&2
  exit 1
fi

echo "[2/10] Prepare disposable match"
if [[ -z "$MATCH_ID" ]]; then
  MATCH_BODY="$(jq -nc \
    --arg name "$MATCH_NAME" --arg teamA "$TEAM_A_ID" --arg teamB "$TEAM_B_ID" \
    '{name:$name,teamAId:$teamA,teamBId:$teamB,format:"T20",totalOvers:20}')"
  CREATED_MATCH="$(request POST "$BASE_URL/matches" "$MATCH_BODY")" \
    || fail_with_body "create disposable match failed" POST "$BASE_URL/matches" "$MATCH_BODY"
  MATCH_ID="$(jq -r '.id // empty' <<<"$CREATED_MATCH")"
  [[ -n "$MATCH_ID" ]] || { echo "ERROR: created match ID missing" >&2; exit 1; }
  echo "    created match=$MATCH_ID"
else
  echo "    using supplied match=$MATCH_ID"
fi

MATCH="$(request GET "$BASE_URL/matches/$MATCH_ID")" || fail_with_body "GET match failed" GET "$BASE_URL/matches/$MATCH_ID"
STATUS="$(jq -r '.status // empty' <<<"$MATCH")"
if [[ "$STATUS" == "COMPLETED" ]]; then
  echo "ERROR: match is already COMPLETED; use a fresh/unplayed fixture" >&2
  exit 1
fi
TEAM_A_FROM_MATCH="$(jq -r '.teamAId // .team_a_id // empty' <<<"$MATCH")"
TEAM_B_FROM_MATCH="$(jq -r '.teamBId // .team_b_id // empty' <<<"$MATCH")"
if [[ -n "$TEAM_A_FROM_MATCH" && "$TEAM_A_FROM_MATCH" != "$TEAM_A_ID" ]]; then
  echo "ERROR: TEAM_A_ID does not match match.teamAId" >&2
  exit 1
fi
if [[ -n "$TEAM_B_FROM_MATCH" && "$TEAM_B_FROM_MATCH" != "$TEAM_B_ID" ]]; then
  echo "ERROR: TEAM_B_ID does not match match.teamBId" >&2
  exit 1
fi

echo "[3/10] Record toss: Team A bats"
TOSS_BODY="$(jq -nc --arg id "$MATCH_ID" --arg winner "$TEAM_A_ID" '{matchId:$id,winnerTeamId:$winner,decision:"BAT"}')"
request POST "$BASE_URL/matches/$MATCH_ID/toss" "$TOSS_BODY" >/dev/null \
  || fail_with_body "record toss failed" POST "$BASE_URL/matches/$MATCH_ID/toss" "$TOSS_BODY"

echo "[4/10] Start innings 1"
INNINGS1_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_A_ID" \
  --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" \
  '{matchId:$match,inningsNumber:1,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
INNINGS1="$(request POST "$BASE_URL/scoring/innings" "$INNINGS1_BODY")" \
  || fail_with_body "start innings 1 failed" POST "$BASE_URL/scoring/innings" "$INNINGS1_BODY"
INNINGS1_ID="$(jq -r '.id // .inningsId // empty' <<<"$INNINGS1")"
[[ -n "$INNINGS1_ID" ]] || { echo "ERROR: innings 1 ID missing" >&2; exit 1; }

OPEN1_BODY="$(jq -nc --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" '{strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler}')"
request POST "$BASE_URL/scoring/innings/$INNINGS1_ID/opening" "$OPEN1_BODY" >/dev/null \
  || fail_with_body "opening innings 1 failed" POST "$BASE_URL/scoring/innings/$INNINGS1_ID/opening" "$OPEN1_BODY"

echo "[5/10] Score innings 1: 120 real legal dot deliveries"
CURRENT_STRIKER="$A_STRIKER_ID"
CURRENT_NON="$A_NON_STRIKER_ID"
CURRENT_BOWLER="$B_BOWLER_ID"
for ((delivery=1; delivery<=120; delivery++)); do
  OVER_NUMBER=$(( (delivery - 1) / 6 ))
  BALL_NUMBER=$(( ((delivery - 1) % 6) + 1 ))

  # Every legal ball is a real API command. After six legal balls the backend
  # rotates strike automatically; alternate bowlers at the over boundary.
  # B_STRIKER_ID is intentionally used as the second Team-B bowler in this
  # disposable two-player-per-team fixture. A player can bat in one innings
  # and bowl in the other role as long as the player belongs to the bowling XI.
  DELIVERY_BODY="$(jq -nc --arg innings "$INNINGS1_ID" \
    --arg striker "$CURRENT_STRIKER" --arg non "$CURRENT_NON" --arg bowler "$CURRENT_BOWLER" \
    --argjson over "$OVER_NUMBER" --argjson ball "$BALL_NUMBER" \
    '{inningsId:$innings,overNumber:$over,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:0,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"
  request POST "$BASE_URL/scoring/innings/$INNINGS1_ID/deliveries" "$DELIVERY_BODY" >/dev/null \
    || fail_with_body "innings 1 delivery $delivery (over $OVER_NUMBER ball $BALL_NUMBER) failed" POST "$BASE_URL/scoring/innings/$INNINGS1_ID/deliveries" "$DELIVERY_BODY"

  if (( BALL_NUMBER == 6 )); then
    tmp="$CURRENT_STRIKER"
    CURRENT_STRIKER="$CURRENT_NON"
    CURRENT_NON="$tmp"
    if [[ "$CURRENT_BOWLER" == "$B_BOWLER_ID" ]]; then
      CURRENT_BOWLER="$B_STRIKER_ID"
    else
      CURRENT_BOWLER="$B_BOWLER_ID"
    fi
  fi
done

SCORE1="$(request GET "$BASE_URL/scoring/innings/$INNINGS1_ID")" || fail_with_body "get innings 1 failed" GET "$BASE_URL/scoring/innings/$INNINGS1_ID"
STATUS1="$(jq -r '.status // empty' <<<"$SCORE1")"
BALLS1="$(jq -r '.legalBalls // .legal_balls // -1' <<<"$SCORE1")"
if [[ "$STATUS1" != "COMPLETED" || "$BALLS1" != "120" ]]; then
  echo "ERROR: innings 1 did not complete correctly: status=$STATUS1 legalBalls=$BALLS1" >&2
  exit 1
fi

expect_rejection "cannot restart completed innings 1" POST "$BASE_URL/scoring/innings" "$INNINGS1_BODY"

WRONG_INNINGS2_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_A_ID" \
  --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" \
  '{matchId:$match,inningsNumber:2,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
expect_rejection "innings 2 rejects wrong batting team" POST "$BASE_URL/scoring/innings" "$WRONG_INNINGS2_BODY"

echo "[6/10] Start innings 2 with Team B"
INNINGS2_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_B_ID" \
  --arg striker "$B_STRIKER_ID" --arg non "$B_NON_STRIKER_ID" --arg bowler "$A_BOWLER_ID" \
  '{matchId:$match,inningsNumber:2,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
INNINGS2="$(request POST "$BASE_URL/scoring/innings" "$INNINGS2_BODY")" \
  || fail_with_body "start innings 2 failed" POST "$BASE_URL/scoring/innings" "$INNINGS2_BODY"
INNINGS2_ID="$(jq -r '.id // .inningsId // empty' <<<"$INNINGS2")"
[[ -n "$INNINGS2_ID" ]] || { echo "ERROR: innings 2 ID missing" >&2; exit 1; }

OPEN2_BODY="$(jq -nc --arg striker "$B_STRIKER_ID" --arg non "$B_NON_STRIKER_ID" --arg bowler "$A_BOWLER_ID" '{strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler}')"
request POST "$BASE_URL/scoring/innings/$INNINGS2_ID/opening" "$OPEN2_BODY" >/dev/null \
  || fail_with_body "opening innings 2 failed" POST "$BASE_URL/scoring/innings/$INNINGS2_ID/opening" "$OPEN2_BODY"

echo "[7/10] Score innings 2: reach target with one real delivery"
DELIVERY2_BODY="$(jq -nc --arg innings "$INNINGS2_ID" \
  --arg striker "$B_STRIKER_ID" --arg non "$B_NON_STRIKER_ID" --arg bowler "$A_BOWLER_ID" \
  --argjson over 0 --argjson ball 1 \
  '{inningsId:$innings,overNumber:$over,ballNumber:$ball,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:1,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"
request POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY" >/dev/null \
  || fail_with_body "innings 2 delivery failed" POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY"

SCORE2="$(request GET "$BASE_URL/scoring/innings/$INNINGS2_ID")" || fail_with_body "get innings 2 failed" GET "$BASE_URL/scoring/innings/$INNINGS2_ID"
STATUS2="$(jq -r '.status // empty' <<<"$SCORE2")"
if [[ "$STATUS2" != "COMPLETED" ]]; then
  echo "ERROR: innings 2 did not complete after reaching target: status=$STATUS2" >&2
  exit 1
fi

expect_rejection "cannot score after match completion" POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY"
expect_rejection "cannot start normal innings 3 after match completion" POST "$BASE_URL/scoring/innings" "$INNINGS2_BODY"

echo "[8/10] Verify result and result idempotency"
RESULT1="$(request GET "$BASE_URL/matches/$MATCH_ID/result")" || fail_with_body "get match result failed" GET "$BASE_URL/matches/$MATCH_ID/result"
RESULT2="$(request GET "$BASE_URL/matches/$MATCH_ID/result")" || fail_with_body "repeat get match result failed" GET "$BASE_URL/matches/$MATCH_ID/result"
RESULT1_CANONICAL="$(jq -S -c . <<<"$RESULT1")"
RESULT2_CANONICAL="$(jq -S -c . <<<"$RESULT2")"
if [[ "$RESULT1_CANONICAL" != "$RESULT2_CANONICAL" ]]; then
  echo "ERROR: match result is not idempotent" >&2
  exit 1
fi
RESULT_STATUS="$(jq -r '.matchStatus // .status // empty' <<<"$RESULT1")"
RESULT_TYPE="$(jq -r '.detailedResultType // .resultType // empty' <<<"$RESULT1")"
WINNER_TEAM_ID="$(jq -r '.winnerTeamId // empty' <<<"$RESULT1")"
if [[ "$RESULT_STATUS" != "COMPLETED" ]]; then
  echo "ERROR: match result status is not COMPLETED: $RESULT_STATUS" >&2
  exit 1
fi
if [[ "$RESULT_TYPE" != "WIN_BY_WICKETS" && "$RESULT_TYPE" != "WIN_BY_RUNS" && "$RESULT_TYPE" != "TIE" ]]; then
  echo "ERROR: unexpected result type: $RESULT_TYPE" >&2
  exit 1
fi
if [[ "$WINNER_TEAM_ID" != "$TEAM_B_ID" ]]; then
  echo "ERROR: expected Team B to win, winner=$WINNER_TEAM_ID" >&2
  exit 1
fi

echo "[9/10] Verify final match status"
MATCH_FINAL="$(request GET "$BASE_URL/matches/$MATCH_ID")" || fail_with_body "GET final match failed" GET "$BASE_URL/matches/$MATCH_ID"
FINAL_STATUS="$(jq -r '.status // empty' <<<"$MATCH_FINAL")"
if [[ "$FINAL_STATUS" != "COMPLETED" ]]; then
  echo "ERROR: final match status is not COMPLETED: $FINAL_STATUS" >&2
  exit 1
fi

if [[ -n "$TOURNAMENT_ID" ]]; then
  echo "[10/10] Verify tournament points table"
  POINTS="$(request GET "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table")" || fail_with_body "get tournament points table failed" GET "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table"
  TEAM_A_ROW="$(jq -c --arg team "$TEAM_A_ID" '.[] | select((.teamId // .team_id // .team?.id // "") == $team)' <<<"$POINTS" | head -n1)"
  TEAM_B_ROW="$(jq -c --arg team "$TEAM_B_ID" '.[] | select((.teamId // .team_id // .team?.id // "") == $team)' <<<"$POINTS" | head -n1)"
  [[ -n "$TEAM_A_ROW" && -n "$TEAM_B_ROW" ]] || { echo "ERROR: both teams missing from tournament points table" >&2; exit 1; }
  TEAM_A_PLAYED="$(jq -r '.played // .matchesPlayed // 0' <<<"$TEAM_A_ROW")"
  TEAM_B_PLAYED="$(jq -r '.played // .matchesPlayed // 0' <<<"$TEAM_B_ROW")"
  TEAM_A_POINTS="$(jq -r '.points // 0' <<<"$TEAM_A_ROW")"
  TEAM_B_POINTS="$(jq -r '.points // 0' <<<"$TEAM_B_ROW")"
  if [[ "$TEAM_A_PLAYED" != "1" || "$TEAM_B_PLAYED" != "1" || "$TEAM_A_POINTS" != "0" || "$TEAM_B_POINTS" != "2" ]]; then
    echo "ERROR: tournament points table mismatch: A played=$TEAM_A_PLAYED points=$TEAM_A_POINTS; B played=$TEAM_B_PLAYED points=$TEAM_B_POINTS" >&2
    exit 1
  fi
  echo "    Team A: played=$TEAM_A_PLAYED points=$TEAM_A_POINTS"
  echo "    Team B: played=$TEAM_B_PLAYED points=$TEAM_B_POINTS"
else
  echo "[10/10] Tournament verification skipped (TOURNAMENT_ID not supplied)"
fi

echo "PASS: complete two-innings scoring lifecycle"
echo "    match=$MATCH_ID"
echo "    innings1=$INNINGS1_ID status=$STATUS1 legalBalls=$BALLS1"
echo "    innings2=$INNINGS2_ID status=$STATUS2"
echo "    resultType=$RESULT_TYPE winnerTeamId=$WINNER_TEAM_ID"
echo "    finalMatchStatus=$FINAL_STATUS"
