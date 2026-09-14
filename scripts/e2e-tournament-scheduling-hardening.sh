#!/usr/bin/env bash
set -euo pipefail

# Tournament scheduling regression. The test deliberately mutates two
# disposable fixtures and restores their original scheduled times on exit.
#
# Required:
#   BASE_URL=http://localhost:8080/api
#   EMAIL=... PASSWORD=... TOURNAMENT_ID=...
#   FIXTURE_A_ID=... FIXTURE_B_ID=...

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
TOURNAMENT_ID="${TOURNAMENT_ID:-}"
FIXTURE_A_ID="${FIXTURE_A_ID:-}"
FIXTURE_B_ID="${FIXTURE_B_ID:-}"

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

if [[ -z "$EMAIL" || -z "$PASSWORD" || -z "$TOURNAMENT_ID" || -z "$FIXTURE_A_ID" || -z "$FIXTURE_B_ID" ]]; then
  echo "Usage: BASE_URL=... EMAIL=... PASSWORD=... TOURNAMENT_ID=... FIXTURE_A_ID=... FIXTURE_B_ID=... $0" >&2
  exit 2
fi

login_payload="$(jq -cn --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')"
login_response="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' --data "$login_payload")"
TOKEN="$(jq -r '.token // .accessToken // .data.token // empty' <<<"$login_response")"
[[ -n "$TOKEN" ]] || { echo "ERROR: login response did not contain a JWT" >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fixture_json() {
  curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures" -H "Authorization: Bearer $TOKEN" |
    jq -c --arg id "$1" '.[] | select(.matchId == $id)'
}

schedule() {
  curl -fsS -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/$1/schedule" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data "$(jq -cn --arg t "$2" '{scheduledAt:$t}')"
}

expect_status() {
  local expected="$1" fixture="$2" timestamp="$3" output="$TMP_DIR/response.json" status
  status="$(curl -sS -o "$output" -w '%{http_code}' -X POST \
    "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/$fixture/schedule" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data "$(jq -cn --arg t "$timestamp" '{scheduledAt:$t}')")"
  if [[ "$status" != "$expected" ]]; then
    echo "ERROR: expected HTTP $expected, got $status" >&2
    cat "$output" >&2
    exit 1
  fi
  echo "    HTTP $status as expected"
}

A="$(fixture_json "$FIXTURE_A_ID")"
B="$(fixture_json "$FIXTURE_B_ID")"
[[ -n "$A" && -n "$B" ]] || { echo "ERROR: both fixture IDs must belong to the tournament" >&2; exit 1; }
[[ "$FIXTURE_A_ID" != "$FIXTURE_B_ID" ]] || { echo "ERROR: fixture IDs must be different" >&2; exit 1; }

ORIGINAL_A="$(jq -r '.scheduledAt // empty' <<<"$A")"
ORIGINAL_B="$(jq -r '.scheduledAt // empty' <<<"$B")"
STATUS_A="$(jq -r '.status // empty' <<<"$A")"
STATUS_B="$(jq -r '.status // empty' <<<"$B")"
[[ "$STATUS_A" != "COMPLETED" && "$STATUS_B" != "COMPLETED" ]] || { echo "ERROR: scheduling regression requires non-completed fixtures" >&2; exit 1; }

restore() {
  if [[ -n "$ORIGINAL_A" ]]; then schedule "$FIXTURE_A_ID" "$ORIGINAL_A" >/dev/null || true; fi
  if [[ -n "$ORIGINAL_B" ]]; then schedule "$FIXTURE_B_ID" "$ORIGINAL_B" >/dev/null || true; fi
}
trap restore EXIT

NOW="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
BASE_EPOCH="$(date -u -d "$NOW" '+%s')"
TIME_A="$(date -u -d "@$((BASE_EPOCH + 7200))" '+%Y-%m-%dT%H:%M:%SZ')"
TIME_B="$(date -u -d "@$((BASE_EPOCH + 10800))" '+%Y-%m-%dT%H:%M:%SZ')"
TIME_C="$(date -u -d "@$((BASE_EPOCH + 14400))" '+%Y-%m-%dT%H:%M:%SZ')"
PAST="$(date -u -d "@$((BASE_EPOCH - 600))" '+%Y-%m-%dT%H:%M:%SZ')"

echo "[1/5] Reject a past scheduling time"
expect_status 400 "$FIXTURE_A_ID" "$PAST"

echo "[2/5] Schedule fixture A at a future time"
schedule "$FIXTURE_A_ID" "$TIME_A" >/dev/null

ECHO_FIXTURE_A="$(fixture_json "$FIXTURE_A_ID")"
[[ "$(jq -r '.scheduledAt' <<<"$ECHO_FIXTURE_A")" == "$TIME_A" ]] || { echo "ERROR: fixture A was not scheduled at requested time" >&2; exit 1; }

echo "[3/5] Reject exact-time conflict for fixture B"
expect_status 409 "$FIXTURE_B_ID" "$TIME_A"


echo "[4/5] Allow fixture B at a different future time and allow fixture A rescheduling"
schedule "$FIXTURE_B_ID" "$TIME_B" >/dev/null
schedule "$FIXTURE_A_ID" "$TIME_C" >/dev/null
[[ "$(jq -r '.scheduledAt' <<<"$(fixture_json "$FIXTURE_B_ID")")" == "$TIME_B" ]] || { echo "ERROR: fixture B reschedule failed" >&2; exit 1; }
[[ "$(jq -r '.scheduledAt' <<<"$(fixture_json "$FIXTURE_A_ID")")" == "$TIME_C" ]] || { echo "ERROR: fixture A reschedule failed" >&2; exit 1; }

echo "[5/5] Verify tournament remains accessible"
curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID" -H "Authorization: Bearer $TOKEN" | jq -e '.id == "'"$TOURNAMENT_ID"'"' >/dev/null

echo
echo "=== TOURNAMENT SCHEDULING HARDENING E2E PASSED ==="
echo "Past times are rejected, exact-time conflicts are rejected, and valid rescheduling is allowed."
