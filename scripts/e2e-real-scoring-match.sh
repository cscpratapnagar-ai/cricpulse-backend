#!/usr/bin/env bash
set -euo pipefail

# Real scoring E2E regression for the normal two-innings match lifecycle.
# This intentionally uses the existing HTTP APIs; it never writes match,
# innings, delivery, result, or tournament rows directly to PostgreSQL.
#
# Required:
#   EMAIL PASSWORD MATCH_ID TEAM_A_ID TEAM_B_ID
#   A_STRIKER_ID A_NON_STRIKER_ID B_BOWLER_ID
#   B_STRIKER_ID B_NON_STRIKER_ID A_BOWLER_ID
#
# Optional:
#   TOURNAMENT_ID - when supplied, the completed match must be represented in
#   the tournament points table. When omitted, tournament verification is skipped.
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

required=(EMAIL PASSWORD MATCH_ID TEAM_A_ID TEAM_B_ID A_STRIKER_ID A_NON_STRIKER_ID B_BOWLER_ID B_STRIKER_ID B_NON_STRIKER_ID A_BOWLER_ID)
for name in "${required[@]}"; do
  if [[ -z "${!name}" ]]; then
    echo "ERROR: $name is required" >&2
    exit 1
  fi
done

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

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
    curl -sS -o /tmp/cricpulse-lifecycle-body -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$body"
  else
    curl -sS -o /tmp/cricpulse-lifecycle-body -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN"
  fi
}

expect_rejection() {
  local label="$1" method="$2" url="$3" body="${4:-}"
  local status
  status="$(request_status "$method" "$url" "$body")"
  if [[ "$status" -lt 400 || "$status" -ge 500 ]]; then
    echo "FAIL: $label expected 4xx, received HTTP $status" >&2
    cat /tmp/cricpulse-lifecycle-body >&2 || true
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
    curl -sS -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" >&2 || true
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
  echo "$LOGIN_RESPONSE" >&2
  exit 1
fi

echo "[2/10] Verify match"
MATCH="$(request GET "$BASE_URL/matches/$MATCH_ID")" || fail_with_body "GET match failed" GET "$BASE_URL/matches/$MATCH_ID"
STATUS="$(jq -r '.status // empty' <<<"$MATCH")"
if [[ "$STATUS" == "COMPLETED" ]]; then
  echo "ERROR: match is already COMPLETED; use an unplayed fixture" >&2
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
INNINGS1_BODY="$(jq -nc \
  --arg match "$MATCH_ID" --arg team "$TEAM_A_ID" \
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
  DELIVERY_BODY="$(jq -nc \
    --arg innings "$INNINGS1_ID" \
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

# A completed innings must be immutable and cannot be restarted as innings 1.
expect_rejection "cannot restart completed innings 1" POST "$BASE_URL/scoring/innings" "$INNINGS1_BODY"

# The second innings must use the team determined by the toss/result of innings 1.
WRONG_INNINGS2_BODY="$(jq -nc \
  --arg match "$MATCH_ID" --arg team "$TEAM_A_ID" \
  --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" \
  '{matchId:$match,inningsNumber:2,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
expect_rejection "innings 2 rejects wrong batting team" POST "$BASE_URL/scoring/innings" "$WRONG_INNINGS2_BODY"

echo "[6/10] Start innings 2 with Team B"
INNINGS2_BODY="$(jq -nc \
  --arg match "$MATCH_ID" --arg team "$TEAM_B_ID" \
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
DELIVERY2_BODY="$(jq -nc \
  --arg innings "$INNINGS2_ID" \
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

# The match should now be completed. A further delivery or normal innings must be rejected.
expect_rejection "cannot score after match completion" POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY"
expect_rejection "cannot start normal innings 3 after match completion" POST "$BASE_URL/scoring/innings" "$INNINGS2_BODY"

echo "[8/10] Verify result and result idempotency"
RESULT1="$(request GET "$BASE_URL/matches/$MATCH_ID/result")" || fail_with_body "get match result failed" GET "$BASE_URL/matches/$MATCH_ID/result"
RESULT2="$(request GET "$BASE_URL/matches/$MATCH_ID/result")" || fail_with_body "repeat get match result failed" GET "$BASE_URL/matches/$MATCH_ID/result"
RESULT1_CANONICAL="$(jq -S -c . <<<"$RESULT1")"
RESULT2_CANONICAL="$(jq -S -c . <<<"$RESULT2")"
if [[ "$RESULT1_CANONICAL" != "$RESULT2_CANONICAL" ]]; then
  echo "ERROR: repeated result retrieval changed the persisted result" >&2
  echo "First : $RESULT1_CANONICAL" >&2
  echo "Second: $RESULT2_CANONICAL" >&2
  exit 1
fi
RESULT_STATUS="$(jq -r '.status // empty' <<<"$RESULT1")"
RESULT_TYPE="$(jq -r '.resultType // .result_type // empty' <<<"$RESULT1")"
WINNER="$(jq -r '.winningTeamId // .winning_team_id // empty' <<<"$RESULT1")"
if [[ "$RESULT_STATUS" != "COMPLETED" ]]; then
  echo "ERROR: match was not completed: status=$RESULT_STATUS" >&2
  exit 1
fi
if [[ "$RESULT_TYPE" != "WIN" && "$RESULT_TYPE" != "TIE" ]]; then
  echo "ERROR: unexpected result type: $RESULT_TYPE" >&2
  exit 1
fi

if [[ "$RESULT_TYPE" == "WIN" && "$WINNER" != "$TEAM_B_ID" ]]; then
  echo "ERROR: expected Team B to win the one-run chase; winner=$WINNER" >&2
  exit 1
fi

echo "[9/10] Verify completed match state"
MATCH_AFTER="$(request GET "$BASE_URL/matches/$MATCH_ID")" || fail_with_body "get completed match failed" GET "$BASE_URL/matches/$MATCH_ID"
FINAL_STATUS="$(jq -r '.status // empty' <<<"$MATCH_AFTER")"
[[ "$FINAL_STATUS" == "COMPLETED" ]] || { echo "ERROR: match status is $FINAL_STATUS after result" >&2; exit 1; }

if [[ -n "$TOURNAMENT_ID" ]]; then
  echo "[10/10] Verify tournament points-table integration"
  POINTS="$(request GET "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table")" \
    || fail_with_body "get tournament points table failed" GET "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table"

  TEAM_A_ROW="$(jq -c --arg team "$TEAM_A_ID" 'if type == "array" then any(.[]?; (.teamId? // .team_id? // empty) == $team) else false end' <<<"$POINTS")"
  TEAM_B_ROW="$(jq -c --arg team "$TEAM_B_ID" 'if type == "array" then any(.[]?; (.teamId? // .team_id? // empty) == $team) else false end' <<<"$POINTS")"
  [[ "$TEAM_A_ROW" == "true" && "$TEAM_B_ROW" == "true" ]] || {
    echo "ERROR: both tournament teams were not present in points table" >&2
    echo "$POINTS" >&2
    exit 1
  }

  TEAM_A_POINTS="$(jq -r --arg team "$TEAM_A_ID" 'first(.[] | select((.teamId? // .team_id? // empty) == $team) | (.points // 0))' <<<"$POINTS")"
  TEAM_B_POINTS="$(jq -r --arg team "$TEAM_B_ID" 'first(.[] | select((.teamId? // .team_id? // empty) == $team) | (.points // 0))' <<<"$POINTS")"
  TEAM_A_PLAYED="$(jq -r --arg team "$TEAM_A_ID" 'first(.[] | select((.teamId? // .team_id? // empty) == $team) | (.played // 0))' <<<"$POINTS")"
  TEAM_B_PLAYED="$(jq -r --arg team "$TEAM_B_ID" 'first(.[] | select((.teamId? // .team_id? // empty) == $team) | (.played // 0))' <<<"$POINTS")"
  if [[ "$TEAM_A_PLAYED" != "1" || "$TEAM_B_PLAYED" != "1" ]]; then
    echo "ERROR: tournament points table did not count the completed match: A played=$TEAM_A_PLAYED, B played=$TEAM_B_PLAYED" >&2
    echo "$POINTS" >&2
    exit 1
  fi
  if [[ "$RESULT_TYPE" == "WIN" && ("$TEAM_A_POINTS" != "0" || "$TEAM_B_POINTS" != "2") ]]; then
    echo "ERROR: tournament points were not updated for the completed result: A=$TEAM_A_POINTS, B=$TEAM_B_POINTS" >&2
    echo "$POINTS" >&2
    exit 1
  fi
else
  echo "[10/10] Tournament points-table verification skipped (TOURNAMENT_ID not supplied)"
fi

echo
echo "=== REAL MATCH LIFECYCLE E2E PASSED ==="
echo "Match       : $MATCH_ID"
echo "Innings 1   : $INNINGS1_ID (120 legal balls, completed)"
echo "Innings 2   : $INNINGS2_ID (target reached, completed)"
echo "Result type : $RESULT_TYPE"
echo "Winner      : ${WINNER:-TIE}"
echo "Verified    : completed-innings immutability, wrong batting team, result idempotency, post-completion guards"
if [[ -n "$TOURNAMENT_ID" ]]; then
  echo "Tournament  : $TOURNAMENT_ID (points-table verified)"
fi
