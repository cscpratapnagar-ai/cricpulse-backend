#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080/api}"
EMAIL="${EMAIL:-}"; PASSWORD="${PASSWORD:-}"; TOURNAMENT_ID="${TOURNAMENT_ID:-}"
DATABASE_USERNAME="${DATABASE_USERNAME:-cricket_app}"; DATABASE_PASSWORD="${DATABASE_PASSWORD:-cricket_dev_password}"
[[ -n "$EMAIL" && -n "$PASSWORD" && -n "$TOURNAMENT_ID" ]] || { echo "ERROR: EMAIL, PASSWORD and TOURNAMENT_ID are required" >&2; exit 2; }
command -v curl >/dev/null; command -v jq >/dev/null; command -v psql >/dev/null
COOKIE_JAR="$(mktemp)"; TMP_DIR="$(mktemp -d)"
trap 'rm -f "$COOKIE_JAR"; rm -rf "$TMP_DIR"' EXIT
curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE_URL/auth/csrf" >/dev/null
CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "$COOKIE_JAR")"
LOGIN_STATUS="$(curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -o "$TMP_DIR/login.json" -w '%{http_code}' -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF_TOKEN" --data "$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
[[ "$LOGIN_STATUS" == "200" ]] || { cat "$TMP_DIR/login.json" >&2; exit 1; }
TOKEN="$(awk '$6 == "CRICPULSE_ACCESS" { value=$7 } END { print value }' "$COOKIE_JAR")"
[[ -n "$TOKEN" ]] || { echo "ERROR: access token missing" >&2; exit 1; }
api() { curl -fsS "$@" -H "Authorization: Bearer $TOKEN" -H 'Accept: application/json'; }
db() { PGPASSWORD="$DATABASE_PASSWORD" psql -h localhost -p 5432 -U "$DATABASE_USERNAME" -d cricket -v ON_ERROR_STOP=1 "$@"; }
mark_winner() {
  local match_id="$1"
  [[ "$match_id" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "ERROR: invalid match id" >&2; exit 1; }
  db -c "UPDATE matches SET status='COMPLETED', result_type='WIN', winning_team_id=team_a_id, winner_team_id=team_a_id, result_margin=1, result_summary='CI playoff winner' WHERE id='$match_id'::uuid" >/dev/null
}
echo "[1/7] Complete all league fixtures with authoritative results"
db -c "UPDATE matches m SET status='COMPLETED', result_type='WIN', winning_team_id=m.team_a_id, winner_team_id=m.team_a_id, result_margin=1, result_summary='CI league winner' FROM tournament_matches tm WHERE tm.tournament_id='$TOURNAMENT_ID'::uuid AND tm.match_id=m.id AND tm.stage='LEAGUE'" >/dev/null
LEAGUE_TOTAL="$(db -Atc "SELECT COUNT(*) FROM tournament_matches WHERE tournament_id='$TOURNAMENT_ID'::uuid AND stage='LEAGUE'")"
LEAGUE_DONE="$(db -Atc "SELECT COUNT(*) FROM tournament_matches tm JOIN matches m ON m.id=tm.match_id WHERE tm.tournament_id='$TOURNAMENT_ID'::uuid AND tm.stage='LEAGUE' AND m.status='COMPLETED'")"
[[ "$LEAGUE_TOTAL" -eq 6 && "$LEAGUE_DONE" -eq 6 ]] || { echo "ERROR: expected six completed league fixtures, got $LEAGUE_DONE/$LEAGUE_TOTAL" >&2; exit 1; }
echo "[2/7] Activate tournament and generate initial playoff fixtures"
api -X PATCH "$BASE_URL/tournaments/$TOURNAMENT_ID/status" -H 'Content-Type: application/json' --data '{"status":"ACTIVE"}' >/dev/null
initial="$(api -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/playoffs/progress")"
[[ "$(jq -r '.status' <<<"$initial")" == "INITIAL_PLAYOFFS_CREATED" ]] || { echo "ERROR: unexpected initial playoff response: $initial" >&2; exit 1; }
Q1_ID="$(jq -r '.qualifier1.matchId' <<<"$initial")"; ELIM_ID="$(jq -r '.eliminator.matchId' <<<"$initial")"
[[ -n "$Q1_ID" && "$Q1_ID" != null && -n "$ELIM_ID" && "$ELIM_ID" != null ]] || { echo "ERROR: initial playoff fixture ids missing" >&2; exit 1; }
fixtures="$(api "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures")"
[[ "$(jq 'length' <<<"$fixtures")" -eq 8 ]] || { echo "ERROR: expected 8 fixtures after initial playoff generation" >&2; exit 1; }
echo "[3/7] Repeated generation must not duplicate Q1 or Eliminator"
repeat="$(api -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/playoffs/progress")"
[[ "$(jq -r '.status' <<<"$repeat")" == "WAITING_FOR_QUALIFIER_RESULTS" ]] || { echo "ERROR: repeated progression did not wait for results: $repeat" >&2; exit 1; }
[[ "$(jq -r '.qualifier1.matchId' <<<"$repeat")" == "$Q1_ID" && "$(jq -r '.eliminator.matchId' <<<"$repeat")" == "$ELIM_ID" ]] || { echo "ERROR: repeated progression changed initial playoff fixtures" >&2; exit 1; }
echo "[4/7] Q1 loser and Eliminator winner must feed Qualifier 2"
mark_winner "$Q1_ID"; mark_winner "$ELIM_ID"
q2_response="$(api -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/playoffs/progress")"
[[ "$(jq -r '.status' <<<"$q2_response")" == "WAITING_FOR_QUALIFIER_2" ]] || { echo "ERROR: Qualifier 2 was not created: $q2_response" >&2; exit 1; }
Q2_ID="$(jq -r '.qualifier2.matchId' <<<"$q2_response")"
[[ -n "$Q2_ID" && "$Q2_ID" != null ]] || { echo "ERROR: Qualifier 2 id missing" >&2; exit 1; }
q2_teams="$(db -Atc "SELECT team_a_id::text || ',' || team_b_id::text FROM matches WHERE id='$Q2_ID'::uuid")"
Q1_WINNER="$(db -Atc "SELECT team_a_id::text FROM matches WHERE id='$Q1_ID'::uuid")"
ELIM_WINNER="$(db -Atc "SELECT team_a_id::text FROM matches WHERE id='$ELIM_ID'::uuid")"
Q1_LOSER="$(db -Atc "SELECT team_b_id::text FROM matches WHERE id='$Q1_ID'::uuid")"
[[ "$q2_teams" == "$Q1_LOSER,$ELIM_WINNER" || "$q2_teams" == "$ELIM_WINNER,$Q1_LOSER" ]] || { echo "ERROR: Qualifier 2 participants are incorrect: $q2_teams" >&2; exit 1; }
echo "[5/7] Qualifier 2 winner must advance to Final with Q1 winner"
mark_winner "$Q2_ID"
final_response="$(api -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/playoffs/progress")"
[[ "$(jq -r '.status' <<<"$final_response")" == "WAITING_FOR_FINAL" ]] || { echo "ERROR: Final was not created: $final_response" >&2; exit 1; }
FINAL_ID="$(jq -r '.finalFixture.matchId' <<<"$final_response")"
[[ -n "$FINAL_ID" && "$FINAL_ID" != null ]] || { echo "ERROR: Final fixture id missing" >&2; exit 1; }
final_teams="$(db -Atc "SELECT team_a_id::text || ',' || team_b_id::text FROM matches WHERE id='$FINAL_ID'::uuid")"
Q2_WINNER="$(db -Atc "SELECT team_a_id::text FROM matches WHERE id='$Q2_ID'::uuid")"
[[ "$final_teams" == "$Q1_WINNER,$Q2_WINNER" || "$final_teams" == "$Q2_WINNER,$Q1_WINNER" ]] || { echo "ERROR: Final participants are incorrect: $final_teams" >&2; exit 1; }
echo "[6/7] Final winner must be crowned champion"
mark_winner "$FINAL_ID"
champion_response="$(api -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/playoffs/progress")"
[[ "$(jq -r '.status' <<<"$champion_response")" == "CHAMPION_CROWNED" ]] || { echo "ERROR: champion was not confirmed: $champion_response" >&2; exit 1; }
CHAMPION_ID="$(jq -r '.championTeamId' <<<"$champion_response")"
[[ -n "$CHAMPION_ID" && "$CHAMPION_ID" != null ]] || { echo "ERROR: champion team id missing" >&2; exit 1; }
echo "[7/7] Final progression is idempotent"
champion_repeat="$(api -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/playoffs/progress")"
[[ "$(jq -r '.status' <<<"$champion_repeat")" == "CHAMPION_CROWNED" && "$(jq -r '.championTeamId' <<<"$champion_repeat")" == "$CHAMPION_ID" ]] || { echo "ERROR: repeated progression changed champion state" >&2; exit 1; }
fixture_count="$(api "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures" | jq 'length')"
[[ "$fixture_count" -eq 10 ]] || { echo "ERROR: expected 10 total fixtures, got $fixture_count" >&2; exit 1; }
echo "=== TOURNAMENT PLAYOFF PROGRESSION E2E PASSED ==="
echo "Q1, Eliminator, Q2, Final and champion progression are verified and idempotent."
