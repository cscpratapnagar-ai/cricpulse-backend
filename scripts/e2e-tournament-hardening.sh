#!/usr/bin/env bash
set -euo pipefail

# Tournament fixture-generation regression.
# Uses an existing DRAFT tournament owned by the supplied user. The first
# generation call must create the complete graph; the second must be idempotent.
# Unsafe lifecycle/scheduling requests are rejected without mutation.

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

echo "[1/7] Verify tournament ownership and current state"
tournament="$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID" -H "Authorization: Bearer $TOKEN")"
status="$(jq -r '.status // empty' <<<"$tournament")"
[[ "$status" == "DRAFT" ]] || { echo "ERROR: fixture generation regression requires DRAFT status, got $status" >&2; exit 1; }
echo "    status=$status"

echo "[2/7] Verify registered teams and current fixtures"
teams="$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/teams" -H "Authorization: Bearer $TOKEN")"
team_count="$(jq 'length' <<<"$teams")"
fixtures_before="$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures" -H "Authorization: Bearer $TOKEN")"
fixture_count_before="$(jq 'length' <<<"$fixtures_before")"
(( team_count >= 2 )) || { echo "ERROR: tournament needs at least 2 registered teams" >&2; exit 1; }
[[ "$fixture_count_before" -eq 0 ]] || { echo "ERROR: fixture generation regression requires an empty fixture set, got $fixture_count_before" >&2; exit 1; }
echo "    teams=$team_count fixtures_before=$fixture_count_before"

echo "[3/7] Generate fixtures once"
generate1="$(curl -fsS -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/generate" -H "Authorization: Bearer $TOKEN" -H 'Accept: application/json')"
generated1="$(jq -r '.generated // -1' <<<"$generate1")"
skipped1="$(jq -r '.skipped // -1' <<<"$generate1")"
total1="$(jq -r '.total // -1' <<<"$generate1")"
[[ "$generated1" =~ ^[0-9]+$ && "$skipped1" =~ ^[0-9]+$ && "$total1" =~ ^[0-9]+$ ]] || { echo "ERROR: first generation response missing counters: $generate1" >&2; exit 1; }
fixture_count_after_first="$(jq 'length' <<<"$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures" -H "Authorization: Bearer $TOKEN")")"
expected_total=$((team_count * (team_count - 1) / 2))
[[ "$generated1" -eq "$expected_total" ]] || { echo "ERROR: expected $expected_total generated fixtures, got $generated1" >&2; exit 1; }
[[ "$skipped1" -eq 0 ]] || { echo "ERROR: first generation skipped $skipped1 pairs" >&2; exit 1; }
[[ "$total1" -eq "$expected_total" ]] || { echo "ERROR: first generation total $total1; expected $expected_total" >&2; exit 1; }
[[ "$fixture_count_after_first" -eq "$expected_total" ]] || { echo "ERROR: expected $expected_total unique fixtures after first generation, got $fixture_count_after_first" >&2; exit 1; }
FIXTURE_ID="$(jq -r '.fixtures[0].matchId // empty' <<<"$generate1")"
FIXTURE_B_ID="$(jq -r '.fixtures[1].matchId // empty' <<<"$generate1")"
[[ -n "$FIXTURE_ID" && -n "$FIXTURE_B_ID" ]] || { echo "ERROR: generated response did not include fixture identifiers" >&2; exit 1; }
if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'E2E_FIXTURE_A_ID=%s\nE2E_FIXTURE_B_ID=%s\n' "$FIXTURE_ID" "$FIXTURE_B_ID" >> "$GITHUB_ENV"
fi
echo "    generated=$generated1 skipped=$skipped1 total=$total1 fixtures=$fixture_count_after_first"

echo "[4/7] Generate fixtures a second time and require idempotency"
generate2="$(curl -fsS -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/generate" -H "Authorization: Bearer $TOKEN" -H 'Accept: application/json')"
generated2="$(jq -r '.generated // -1' <<<"$generate2")"
skipped2="$(jq -r '.skipped // -1' <<<"$generate2")"
total2="$(jq -r '.total // -1' <<<"$generate2")"
fixture_count_after_second="$(jq 'length' <<<"$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures" -H "Authorization: Bearer $TOKEN")")"
[[ "$generated2" == "0" ]] || { echo "ERROR: second generation created $generated2 new fixtures" >&2; exit 1; }
[[ "$skipped2" -eq "$expected_total" ]] || { echo "ERROR: second generation skipped $skipped2 pairs; expected $expected_total" >&2; exit 1; }
[[ "$total2" -eq "$expected_total" ]] || { echo "ERROR: second generation total $total2; expected $expected_total" >&2; exit 1; }
[[ "$fixture_count_after_second" -eq "$fixture_count_after_first" ]] || { echo "ERROR: fixture count changed from $fixture_count_after_first to $fixture_count_after_second" >&2; exit 1; }
echo "    generated=$generated2 skipped=$skipped2 total=$total2 fixtures=$fixture_count_after_second"

echo "[5/7] Reject unsupported fixture stage"
TEAM_ID="$(jq -r '.[0].id // empty' <<<"$teams")"
[[ -n "$TEAM_ID" ]] || { echo "ERROR: team identifier missing" >&2; exit 1; }
expect_status 400 POST "$BASE_URL/tournaments/$TOURNAMENT_ID/matches/$FIXTURE_ID?stage=INVALID_STAGE"

echo "[6/7] Reject duplicate team registration and past scheduling"
expect_status 409 POST "$BASE_URL/tournaments/$TOURNAMENT_ID/teams/$TEAM_ID"
PAST_TIME="$(date -u -d '10 minutes ago' '+%Y-%m-%dT%H:%M:%SZ')"
expect_status 400 POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/$FIXTURE_ID/schedule" "$(jq -cn --arg t "$PAST_TIME" '{scheduledAt:$t}')"

echo "[7/7] Verify points table remains readable"
points="$(curl -fsS "$BASE_URL/tournaments/$TOURNAMENT_ID/points-table" -H "Authorization: Bearer $TOKEN")"
point_rows="$(jq 'length' <<<"$points")"
(( point_rows >= 1 )) || { echo "ERROR: points table returned no teams" >&2; exit 1; }
echo "    point_rows=$point_rows"

echo
echo "=== TOURNAMENT FIXTURE IDEMPOTENCY E2E PASSED ==="
echo "Fixture generation is idempotent for all registered unordered team pairs."
