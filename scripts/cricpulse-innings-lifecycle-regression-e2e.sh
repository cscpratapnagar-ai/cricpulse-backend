#!/usr/bin/env bash
set -euo pipefail

# Lifecycle regression test for the normal two-innings match contract.
# This script intentionally verifies API rejection paths only; it does not
# mutate PostgreSQL directly and it does not implement Super Over behavior.
#
# Required:
#   EMAIL PASSWORD MATCH_ID TEAM_A_ID TEAM_B_ID
#   A_STRIKER_ID A_NON_STRIKER_ID B_BOWLER_ID
#
# Optional:
#   BASE_URL (default http://localhost:8080/api)
#
# The supplied MATCH_ID must be an unplayed fixture. The test records the toss,
# starts innings 1, and then verifies the lifecycle guards that can be checked
# before innings 1 is completed. It does not complete the supplied match.

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
MATCH_ID="${MATCH_ID:-}"
TEAM_A_ID="${TEAM_A_ID:-}"
TEAM_B_ID="${TEAM_B_ID:-}"
A_STRIKER_ID="${A_STRIKER_ID:-}"
A_NON_STRIKER_ID="${A_NON_STRIKER_ID:-}"
B_BOWLER_ID="${B_BOWLER_ID:-}"

required=(EMAIL PASSWORD MATCH_ID TEAM_A_ID TEAM_B_ID A_STRIKER_ID A_NON_STRIKER_ID B_BOWLER_ID)
for name in "${required[@]}"; do
  [[ -n "${!name}" ]] || { echo "ERROR: $name is required" >&2; exit 1; }
done

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

request_status() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -o /tmp/cricpulse-lifecycle-body -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body"
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

LOGIN_RESPONSE="$(curl -sS -f -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
TOKEN="$(jq -r '.accessToken // .token // .data.accessToken // empty' <<<"$LOGIN_RESPONSE")"
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || { echo "ERROR: no access token" >&2; exit 1; }

echo "[1] Verify fixture"
MATCH="$(curl -sS -f "$BASE_URL/matches/$MATCH_ID" -H "Authorization: Bearer $TOKEN")"
STATUS="$(jq -r '.status // empty' <<<"$MATCH")"
[[ "$STATUS" != "COMPLETED" ]] || { echo "ERROR: supplied match is already completed" >&2; exit 1; }

TOSS_BODY="$(jq -nc --arg id "$MATCH_ID" --arg winner "$TEAM_A_ID" '{matchId:$id,winnerTeamId:$winner,decision:"BAT"}')"

# Before toss, a fresh match must not permit an innings. Since the match is
# currently unplayed, this call is intentionally made before recording toss.
INNINGS1_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_A_ID" \
  --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" \
  '{matchId:$match,inningsNumber:1,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
expect_rejection "cannot start innings before toss" POST "$BASE_URL/scoring/innings" "$INNINGS1_BODY"

echo "[2] Record toss and start innings 1"
curl -sS -f -X POST "$BASE_URL/matches/$MATCH_ID/toss" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$TOSS_BODY" >/dev/null
INNINGS1="$(curl -sS -f -X POST "$BASE_URL/scoring/innings" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$INNINGS1_BODY")"
INNINGS1_ID="$(jq -r '.id // .inningsId // empty' <<<"$INNINGS1")"
[[ -n "$INNINGS1_ID" ]] || { echo "ERROR: innings 1 ID missing" >&2; exit 1; }

# Innings 2 cannot begin while innings 1 is LIVE.
INNINGS2_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_B_ID" \
  --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" \
  '{matchId:$match,inningsNumber:2,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
expect_rejection "cannot start innings 2 before innings 1 completes" POST "$BASE_URL/scoring/innings" "$INNINGS2_BODY"

# A third normal innings is not part of the normal match lifecycle.
INNINGS3_BODY="$(jq -nc --arg match "$MATCH_ID" --arg team "$TEAM_A_ID" \
  --arg striker "$A_STRIKER_ID" --arg non "$A_NON_STRIKER_ID" --arg bowler "$B_BOWLER_ID" \
  '{matchId:$match,inningsNumber:3,battingTeamId:$team,strikerId:$striker,nonStrikerId:$non,currentBowlerId:$bowler}')"
expect_rejection "cannot start normal innings 3" POST "$BASE_URL/scoring/innings" "$INNINGS3_BODY"

echo
echo "=== INNINGS LIFECYCLE REGRESSION PASSED ==="
echo "Match      : $MATCH_ID"
echo "Innings 1  : $INNINGS1_ID (LIVE; test fixture intentionally left uncompleted)"
echo "Verified   : toss prerequisite, innings ordering, normal innings boundary"
