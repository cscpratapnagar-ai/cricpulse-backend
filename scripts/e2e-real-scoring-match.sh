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
#
# When MATCH_ID is omitted, the script creates a fresh 20-over fixture through
# POST /matches. This makes the lifecycle regression repeatable without
# consuming a manually prepared match. The created fixture is intentionally
# retained because the current API has no safe match-delete contract.
#
# The test uses a 20-over match: innings 1 is completed by 120 legal dot balls,
# innings 2 reaches the one-run target on its first legal delivery.

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
for ((ball=1; ball<=120; ball++)); do
  DELIVERY_BODY="$(jq -nc --arg innings "$INNINGS1_ID" \
    --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" \
    '{inningsId:$innings,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:0,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"
  request POST "$BASE_URL/scoring/innings/$INNINGS1_ID/deliveries" "$DELIVERY_BODY" >/dev/null \
    || fail_with_body "innings 1 delivery $ball failed" POST "$BASE_URL/scoring/innings/$INNINGS1_ID/deliveries" "$DELIVERY_BODY"
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
  '{inningsId:$innings,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:1,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"
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
  echo "ERROR: repeated result retrieval changed the persisted result" >&2
  exit 1
fi
RESULT_STATUS="$(jq -r '.status // empty' <<<"$RESULT1")"
RESULT_TYPE="$(jq -r '.resultType // .result_type // empty' <<<"$RESULT1")"
WINNER="$(jq -r '.winningTeamId // .winning_team_id // empty' <<<"$RESULT1")"
if [[ "$RESULT_STATUS" != "COMPLETED" ]]; then
  echo "ERROR: match was not completed: status=$RESULT_STATUS" >&2
  exit 1
fi
if [[ "$RESULT_TYPE" != "WIN_BY_WICKETS" && "$RESULT_TYPE" != "WIN_BY_RUNS" && "$RESULT_TYPE" != "TIE" ]]; then
  echo "ERROR: unexpected detailed result type: $RESULT_TYPE" >&2
  exit 1
fi
if [[ "$RESULT_TYPE" != "WIN_BY_WICKETS" || "$WINNER" != "$TEAM_B_ID" ]]; then
  echo "ERROR: expected Team B to win the one-run chase by wickets; type=$RESULT_TYPE winner=$WINNER" >&2
  exit 1
fi

echo "[9/10] Verify completed match state"
MATCH_AFTER="$(request GET "$BASE_URL/matches/$MATCH_ID")" || fail_with_body "get completed match failed" GET "$BASE_URL/matches/$MATCH_ID"
FINAL_STATUS="$(jq -r '.status // empty' <<<"$MATCH_AFTER")"
[[ "$FINAL_STATUS" == "COMPLETED" ]] || { echo "ERROR: match status is $FINAL_STATUS after result" >&2; exit 1; }

if [[ -n "$TOURNAMENT_ID" ]]; then
  echo "[10/10] Verify tournament points table"
  POINTS="$(request GET "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table")" || fail_with_body "points table request failed" GET "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table"
  ROW_A="$(jq -c --arg id "$TEAM_A_ID" '.[] | select((.teamId // .team_id) == $id)' <<<"$POINTS" | head -n 1)"
  ROW_B="$(jq -c --arg id "$TEAM_B_ID" '.[] | select((.teamId // .team_id) == $id)' <<<"$POINTS" | head -n 1)"
  [[ -n "$ROW_A" && -n "$ROW_B" ]] || { echo "ERROR: both match teams are missing from tournament points table" >&2; exit 1; }
  PLAYED_A="$(jq -r '.played // 0' <<<"$ROW_A")"
  PLAYED_B="$(jq -r '.played // 0' <<<"$ROW_B")"
  POINTS_A="$(jq -r '.points // 0' <<<"$ROW_A")"
  POINTS_B="$(jq -r '.points // 0' <<<"$ROW_B")"
  [[ "$PLAYED_A" == "1" && "$PLAYED_B" == "1" ]] || { echo "ERROR: expected both teams played=1; A=$PLAYED_A B=$PLAYED_B" >&2; exit 1; }
  [[ "$POINTS_A" == "0" && "$POINTS_B" == "2" ]] || { echo "ERROR: expected points A=0 B=2; A=$POINTS_A B=$POINTS_B" >&2; exit 1; }
else
  echo "[10/10] Tournament points verification skipped (TOURNAMENT_ID not supplied)"
fi

echo
echo "=== COMPLETE MATCH LIFECYCLE E2E PASSED ==="
echo "Match      : $MATCH_ID"
echo "Innings 1  : 120 legal balls -> COMPLETED"
echo "Innings 2  : target reached -> COMPLETED"
echo "Result     : $RESULT_TYPE / winner=$WINNER"
echo "Final state: MATCH COMPLETED"
