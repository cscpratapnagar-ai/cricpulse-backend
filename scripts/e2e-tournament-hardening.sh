#!/usr/bin/env bash
set -euo pipefail

# Tournament API hardening regression test.
# Uses an existing tournament owned by the supplied user and intentionally
# exercises only negative/idempotency paths; it must not alter tournament
# state when the API behaves correctly.
#
# Required:
#   BASE_URL=http://localhost:8080/api
#   EMAIL=... PASSWORD=... TOURNAMENT_ID=...

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
TOURNAMENT_ID="${TOURNAMENT_ID:-}"

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

if [[ -z "$EMAIL" || -z "$PASSWORD" || -z "$TOURNAMENT_ID" ]]; then
  echo "Usage: BASE_URL=... EMAIL=... PASSWORD=... TOURNAMENT_ID=... $0" >&2
  exit 2
fi

login_payload="$(jq -cn --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')"
login_response="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' --data "$login_payload")"
TOKEN="$(jq -r '.token // .accessToken // .data.token // empty' <<<"$login_response")"
[[ -n "$TOKEN" ]] || { echo "ERROR: login response did not contain a JWT" >&2; exit 1; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

expect_status() {
  local expected="$1" method="$2" url="$3" body="${4:-}" output="$TMP_DIR/response.json" status
  if [[ -n "$body" ]]; then
    status="$(curl -sS -o "$output" -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data "$body")"
  else
    status="$(curl -sS -o "$output" -w '%{http_code}' -X "$method" "$url" \
      -H "Authorization: Bearer $TOKEN" -H 'Accept: application/json')"
  fi
  if [[ "$status" != "$expected" ]]; then
    echo "ERROR: expected HTTP $expected, got $status for $method $url" >&2
    cat "$output" >&2
    exit 1
  fi
  echo "    HTTP $status as expected"
}

echo "[1/5] Verify tournament ownership and state"
tournament="$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID" -H "Authorization: Bearer $TOKEN")"
status="$(jq -r '.status // empty' <<<"$tournament")"
[[ -n "$status" ]] || { echo "ERROR: tournament response has no status" >&2; exit 1; }
echo "    status=$status"

echo "[2/5] Verify registered teams and fixtures"
teams="$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/teams" -H "Authorization: Bearer $TOKEN")"
team_count="$(jq 'length' <<<"$teams")"
(( team_count >= 1 )) || { echo "ERROR: tournament has no registered teams" >&2; exit 1; }
fixtures="$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures" -H "Authorization: Bearer $TOKEN")"
fixture_count="$(jq 'length' <<<"$fixtures")"
(( fixture_count >= 1 )) || { echo "ERROR: tournament has no fixtures" >&2; exit 1; }
FIXTURE_ID="$(jq -r '.[0].matchId // empty' <<<"$fixtures")"
TEAM_ID="$(jq -r '.[0].teamId // empty' <<<"$teams")"
[[ -n "$FIXTURE_ID" && -n "$TEAM_ID" ]] || { echo "ERROR: fixture/team identifiers missing" >&2; exit 1; }
echo "    teams=$team_count fixtures=$fixture_count"

echo "[3/5] Reject unsupported fixture stage"
expect_status 400 POST "$BASE_URL/tournaments/$TOURNAMENT_ID/matches/$FIXTURE_ID?stage=INVALID_STAGE"

echo "[4/5] Reject duplicate team registration"
expect_status 409 POST "$BASE_URL/tournaments/$TOURNAMENT_ID/teams/$TEAM_ID"

echo "[5/5] Verify points table remains readable"
points="$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table" -H "Authorization: Bearer $TOKEN")"
point_rows="$(jq 'length' <<<"$points")"
(( point_rows >= 1 )) || { echo "ERROR: points table returned no teams" >&2; exit 1; }
echo "    point_rows=$point_rows"

echo
echo "=== TOURNAMENT HARDENING E2E PASSED ==="
echo "No successful mutation was performed by this regression script."
