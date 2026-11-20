#!/usr/bin/env bash
set -euo pipefail

# Regression coverage for single- and double-round-robin fixture generation.
# Creates disposable teams/tournaments under the supplied CI owner.

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"
PASSWORD="${PASSWORD:-}"
RUN_KEY="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"

command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }
[[ -n "$EMAIL" && -n "$PASSWORD" ]] || { echo "ERROR: EMAIL and PASSWORD are required" >&2; exit 2; }

TMP_DIR="$(mktemp -d)"
COOKIE_JAR="$TMP_DIR/cookies"
trap 'rm -rf "$TMP_DIR"' EXIT

curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE_URL/auth/csrf" >/dev/null
CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$COOKIE_JAR")"
[[ -n "$CSRF_TOKEN" ]] || { echo "ERROR: CSRF cookie missing" >&2; exit 1; }
LOGIN_STATUS="$(curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -o "$TMP_DIR/login.json" -w '%{http_code}' \
  -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
  --data "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
[[ "$LOGIN_STATUS" == "200" ]] || { echo "ERROR: login HTTP $LOGIN_STATUS" >&2; cat "$TMP_DIR/login.json" >&2; exit 1; }
TOKEN="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$COOKIE_JAR")"
[[ -n "$TOKEN" ]] || { echo "ERROR: HttpOnly access cookie missing" >&2; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

api_post() {
  curl -fsS -X POST "$1" "${AUTH[@]}" -H 'Content-Type: application/json' --data "$2"
}
create_tournament() {
  api_post "$BASE_URL/tournaments" "$(jq -nc --arg n "$1" '{name:$n,format:"T20",overs:20,location:"CI",startDate:null}')" |
    jq -r '.id'
}
create_team() {
  api_post "$BASE_URL/teams" "$(jq -nc --arg n "$1" '{name:$n,city:"CI"}')" | jq -r '.id'
}

echo "[1/7] Create four disposable teams"
TEAM_IDS=()
for suffix in A B C D; do
  id="$(create_team "RR Regression $suffix $RUN_KEY")"
  [[ -n "$id" && "$id" != "null" ]] || { echo "ERROR: team creation failed ($suffix)" >&2; exit 1; }
  TEAM_IDS+=("$id")
done

echo "[2/7] Create a double-round-robin tournament"
DOUBLE_ID="$(create_tournament "Double RR Regression $RUN_KEY")"
[[ -n "$DOUBLE_ID" && "$DOUBLE_ID" != "null" ]] || { echo "ERROR: double RR tournament creation failed" >&2; exit 1; }
for id in "${TEAM_IDS[@]}"; do
  curl -fsS -X POST "$BASE_URL/tournaments/$DOUBLE_ID/teams/$id" "${AUTH[@]}" >/dev/null
done

echo "[3/7] Generate DOUBLE_ROUND_ROBIN fixtures"
DOUBLE_RESPONSE="$(curl -fsS -X POST "$BASE_URL/tournaments/$DOUBLE_ID/fixtures/generate?mode=DOUBLE_ROUND_ROBIN" "${AUTH[@]}" -H 'Accept: application/json')"
jq -e '.generated == 12 and .skipped == 0 and .totalPairs == 12 and (.fixtures | length) == 12' <<<"$DOUBLE_RESPONSE" >/dev/null || {
  echo "ERROR: expected 12 generated double-round-robin fixtures; response=$DOUBLE_RESPONSE" >&2; exit 1;
}
DOUBLE_FIXTURES="$(curl -fsS "$BASE_URL/tournaments/$DOUBLE_ID/fixtures" "${AUTH[@]}")"
[[ "$(jq 'length' <<<"$DOUBLE_FIXTURES")" -eq 12 ]] || { echo "ERROR: expected 12 persisted fixtures" >&2; exit 1; }

echo "[4/7] Verify every pair plays twice with reversed home/away"
jq -e '
  length == 12 and
  all(.[]; .stage == "LEAGUE") and
  ([.[] | select(.teamAId < .teamBId) | [.teamAId,.teamBId] | join("|")] | length) == 6 and
  ([.[] | select(.teamAId > .teamBId) | [.teamBId,.teamAId] | join("|")] | length) == 6 and
  ([.[] | ([.teamAId,.teamBId] | sort | join("|"))] | group_by(.) | all(.[]; length == 2)) and
  ([.[] | [.teamAId,.teamBId] | join("|")] | unique | length) == 12
' <<<"$DOUBLE_FIXTURES" >/dev/null || {
  echo "ERROR: directed pair/home-away reversal invariant failed" >&2
  jq '.' <<<"$DOUBLE_FIXTURES" >&2
  exit 1
}

echo "[5/7] Verify second generation is idempotent"
SECOND="$(curl -fsS -X POST "$BASE_URL/tournaments/$DOUBLE_ID/fixtures/generate?mode=DOUBLE_ROUND_ROBIN" "${AUTH[@]}")"
jq -e '.generated == 0 and .skipped == 12 and .totalPairs == 12 and (.fixtures | length) == 12' <<<"$SECOND" >/dev/null || {
  echo "ERROR: repeated double generation was not idempotent: $SECOND" >&2; exit 1;
}
[[ "$(curl -fsS "$BASE_URL/tournaments/$DOUBLE_ID/fixtures" "${AUTH[@]}" | jq 'length')" -eq 12 ]] || {
  echo "ERROR: repeated generation changed persisted fixture count" >&2; exit 1;
}

echo "[6/7] Verify default mode remains SINGLE_ROUND_ROBIN"
SINGLE_ID="$(create_tournament "Single RR Regression $RUN_KEY")"
for id in "${TEAM_IDS[@]}"; do
  curl -fsS -X POST "$BASE_URL/tournaments/$SINGLE_ID/teams/$id" "${AUTH[@]}" >/dev/null
done
SINGLE_RESPONSE="$(curl -fsS -X POST "$BASE_URL/tournaments/$SINGLE_ID/fixtures/generate" "${AUTH[@]}")"
jq -e '.generated == 6 and .skipped == 0 and .totalPairs == 6 and (.fixtures | length) == 6' <<<"$SINGLE_RESPONSE" >/dev/null || {
  echo "ERROR: default mode no longer generates six single-round-robin fixtures: $SINGLE_RESPONSE" >&2; exit 1;
}
SINGLE_FIXTURES="$(curl -fsS "$BASE_URL/tournaments/$SINGLE_ID/fixtures" "${AUTH[@]}")"
jq -e 'length == 6 and ([.[] | ([.teamAId,.teamBId] | sort | join("|"))] | unique | length) == 6' <<<"$SINGLE_FIXTURES" >/dev/null || {
  echo "ERROR: single-round-robin fixture uniqueness invariant failed" >&2; exit 1;
}

echo "[7/7] Reject unsupported generation mode"
INVALID_STATUS="$(curl -sS -o "$TMP_DIR/invalid.json" -w '%{http_code}' -X POST \
  "$BASE_URL/tournaments/$DOUBLE_ID/fixtures/generate?mode=KNOCKOUT" "${AUTH[@]}")"
[[ "$INVALID_STATUS" == "400" ]] || {
  echo "ERROR: expected HTTP 400 for unsupported mode, got $INVALID_STATUS" >&2
  cat "$TMP_DIR/invalid.json" >&2
  exit 1
}

echo
echo "=== DOUBLE ROUND-ROBIN REGRESSION E2E PASSED ==="
echo "Double RR: 12 fixtures, 6 reversed pairings, repeat generation adds 0."
echo "Single RR: default mode remains 6 fixtures."
