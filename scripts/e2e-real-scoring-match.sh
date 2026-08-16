#!/usr/bin/env bash
set -euo pipefail

# Real scoring E2E smoke test.
# This intentionally uses the existing HTTP scoring APIs; it never writes match,
# innings, delivery, or result rows directly to PostgreSQL.
#
# Required:
#   EMAIL PASSWORD MATCH_ID TEAM_A_ID TEAM_B_ID
#   A_STRIKER_ID A_NON_STRIKER_ID B_BOWLER_ID
#   B_STRIKER_ID B_NON_STRIKER_ID A_BOWLER_ID
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

echo "[1/8] Login"
LOGIN_RESPONSE="$(curl -sS -f -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$LOGIN_RESPONSE")"
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "ERROR: login succeeded but no access token was found" >&2
  echo "$LOGIN_RESPONSE" >&2
  exit 1
fi

echo "[2/8] Verify match"
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

echo "[3/8] Record toss: Team A bats"
TOSS_BODY="$(jq -nc --arg id "$MATCH_ID" --arg winner "$TEAM_A_ID" '{matchId:$id,winnerTeamId:$winner,decision:"BAT"}')"
request POST "$BASE_URL/matches/$MATCH_ID/toss" "$TOSS_BODY" >/dev/null \
  || fail_with_body "record toss failed" POST "$BASE_URL/matches/$MATCH_ID/toss" "$TOSS_BODY"

echo "[4/8] Start innings 1"
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

echo "[5/8] Score innings 1: 120 real legal dot deliveries"
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

echo "[6/8] Start innings 2"
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

echo "[7/8] Score innings 2: reach target with one real delivery"
DELIVERY2_BODY="$(jq -nc \
  --arg innings "$INNINGS2_ID" \
  --arg striker "$B_STRIKER_ID" --arg non "$B_NON_STRIKER_ID" --arg bowler "$A_BOWLER_ID" \
  '{inningsId:$innings,strikerId:$striker,nonStrikerId:$non,bowlerId:$bowler,batRuns:1,extraRuns:0,extraType:null,wicketType:null,dismissedPlayerId:null,newBatterId:null}')"
request POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY" >/dev/null \
  || fail_with_body "innings 2 delivery failed" POST "$BASE_URL/scoring/innings/$INNINGS2_ID/deliveries" "$DELIVERY2_BODY"

echo "[8/8] Verify real match result"
RESULT="$(request GET "$BASE_URL/matches/$MATCH_ID/result")" || fail_with_body "get match result failed" GET "$BASE_URL/matches/$MATCH_ID/result"
RESULT_STATUS="$(jq -r '.status // empty' <<<"$RESULT")"
RESULT_TYPE="$(jq -r '.resultType // .result_type // empty' <<<"$RESULT")"
WINNER="$(jq -r '.winningTeamId // .winning_team_id // empty' <<<"$RESULT")"
if [[ "$RESULT_STATUS" != "COMPLETED" ]]; then
  echo "ERROR: match was not completed: status=$RESULT_STATUS" >&2
  exit 1
fi
if [[ "$RESULT_TYPE" != "WIN" && "$RESULT_TYPE" != "TIE" ]]; then
  echo "ERROR: unexpected result type: $RESULT_TYPE" >&2
  exit 1
fi

POINTS="$(request GET "$BASE_URL/tournaments/${TOURNAMENT_ID:-}/points-table" 2>/dev/null || true)"

echo
echo "=== REAL SCORING E2E PASSED ==="
echo "Match       : $MATCH_ID"
echo "Innings 1   : $INNINGS1_ID (120 legal balls, completed)"
echo "Innings 2   : $INNINGS2_ID (target reached, completed)"
echo "Result type : $RESULT_TYPE"
echo "Winner      : ${WINNER:-TIE}"
