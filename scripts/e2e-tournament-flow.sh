#!/usr/bin/env bash
set -euo pipefail

# Tournament lifecycle smoke test.
# This script intentionally uses the public HTTP API and does not mutate
# production application code. It requires a running local backend and a
# tournament owned by the supplied login user.
#
# Required:
#   BASE_URL=http://localhost:8080/api
#   EMAIL=rahul.test2026@gmail.com
#   PASSWORD=Test@12345
#   TOURNAMENT_ID=<existing tournament UUID>
#
# Optional:
#   EXPECT_STATUS_AFTER_ACTIVE=ACTIVE
#   EXPECT_STATUS_AFTER_COMPLETE=COMPLETED

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
TOURNAMENT_ID="${TOURNAMENT_ID:-}"
EXPECT_STATUS_AFTER_ACTIVE="${EXPECT_STATUS_AFTER_ACTIVE:-ACTIVE}"
EXPECT_STATUS_AFTER_COMPLETE="${EXPECT_STATUS_AFTER_COMPLETE:-COMPLETED}"

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

if [[ -z "$EMAIL" || -z "$PASSWORD" || -z "$TOURNAMENT_ID" ]]; then
  cat >&2 <<'EOF'
Usage:
  BASE_URL=http://localhost:8080/api \
  EMAIL=... PASSWORD=... TOURNAMENT_ID=... \
  ./scripts/e2e-tournament-flow.sh

This smoke test does not create or alter matches. It verifies the existing
 tournament and its lifecycle/points-table API contract. A tournament must
already be owned by the supplied user.
EOF
  exit 2
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

request() {
  local method="$1" url="$2" body="${3:-}" output="$TMP_DIR/response.json"
  if [[ -n "$body" ]]; then
    curl -fsS -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      --data "$body" -o "$output"
  else
    curl -fsS -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Accept: application/json' -o "$output"
  fi
  cat "$output"
}

login_payload="$(jq -cn --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')"
login_response="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' --data "$login_payload")"
TOKEN="$(jq -r '.token // .accessToken // .data.token // empty' <<<"$login_response")"

if [[ -z "$TOKEN" ]]; then
  echo "ERROR: login succeeded but no JWT token was found in the response." >&2
  echo "$login_response" >&2
  exit 1
fi

echo "[1/4] GET tournament"
tournament="$(request GET "$BASE_URL/tournaments/$TOURNAMENT_ID")"
status="$(jq -r '.status // empty' <<<"$tournament")"
[[ -n "$status" ]] || { echo "ERROR: tournament response has no status" >&2; exit 1; }
echo "    status=$status"

echo "[2/4] GET fixtures"
fixtures="$(request GET "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures")"
fixture_count="$(jq 'length' <<<"$fixtures")"
echo "    fixtures=$fixture_count"
(( fixture_count > 0 )) || { echo "ERROR: tournament has no fixtures" >&2; exit 1; }

if [[ "$status" == "DRAFT" ]]; then
  echo "[3/4] DRAFT -> ACTIVE validation"
  activated="$(request PATCH "$BASE_URL/tournaments/$TOURNAMENT_ID/status" '{"status":"ACTIVE"}')"
  activated_status="$(jq -r '.status // empty' <<<"$activated")"
  [[ "$activated_status" == "$EXPECT_STATUS_AFTER_ACTIVE" ]] || {
    echo "ERROR: expected $EXPECT_STATUS_AFTER_ACTIVE, got $activated_status" >&2
    exit 1
  }
else
  echo "[3/4] lifecycle transition skipped (current status=$status)"
fi

echo "[4/4] GET points table"
points="$(request GET "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table")"
point_rows="$(jq 'length' <<<"$points")"
echo "    point_rows=$point_rows"
(( point_rows > 0 )) || { echo "ERROR: points table returned no teams" >&2; exit 1; }

if [[ "$status" == "ACTIVE" ]]; then
  echo "NOTE: ACTIVE -> COMPLETED is intentionally not attempted. The API correctly
requires every fixture to be COMPLETED; use the existing scoring engine first."
fi

echo
echo "=== TOURNAMENT E2E SMOKE TEST PASSED ==="
echo "Tournament: $TOURNAMENT_ID"
echo "Fixtures  : $fixture_count"
echo "Teams     : $point_rows"
