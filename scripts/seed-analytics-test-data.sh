#!/usr/bin/env bash
set -euo pipefail

# Creates a complete local tournament dataset for Analytics and Qualification UI testing.
# Uses only verified tournament/team/fixture APIs, then seeds the aggregate innings data
# consumed by the existing points-table and qualification implementations.

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-cricketpulse-postgres}"
DB_USER="${DB_USER:-cricket_app}"
DB_NAME="${DB_NAME:-cricket}"
OWNER_EMAIL="${OWNER_EMAIL:-rahul.test2026@gmail.com}"
OWNER_PASSWORD="${OWNER_PASSWORD:-Test@12345}"
OWNER_NAME="${OWNER_NAME:-Rahul Test}"
OWNER_PHONE="${OWNER_PHONE:-9876543211}"
SUFFIX="${SUFFIX:-$(date +%Y%m%d-%H%M%S)}"

command -v curl >/dev/null || { echo "ERROR: curl is required"; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required"; exit 1; }
command -v docker >/dev/null || { echo "ERROR: docker is required"; exit 1; }

docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1 || {
  echo "ERROR: PostgreSQL container '$POSTGRES_CONTAINER' was not found."; exit 1;
}

json() {
  curl -sS --fail-with-body -H "Content-Type: application/json" "$@"
}

echo "== CricPulse Analytics / Qualification Test Data Seeder =="
echo "API: $BASE_URL"
echo "PostgreSQL: $POSTGRES_CONTAINER / $DB_NAME"
echo

owner_payload=$(jq -nc \
  --arg fullName "$OWNER_NAME" \
  --arg email "$OWNER_EMAIL" \
  --arg phone "$OWNER_PHONE" \
  --arg password "$OWNER_PASSWORD" \
  '{fullName:$fullName,email:$email,phone:$phone,password:$password}')

if ! json -X POST "$BASE_URL/users" -d "$owner_payload" >/tmp/cricpulse_analytics_owner.json 2>/tmp/cricpulse_analytics_owner.err; then
  echo "Owner registration skipped (account may already exist)."
fi

owner_login=$(json -X POST "$BASE_URL/auth/login" \
  -d "$(jq -nc --arg email "$OWNER_EMAIL" --arg password "$OWNER_PASSWORD" '{email:$email,password:$password}')")
OWNER_TOKEN=$(jq -r '.accessToken' <<<"$owner_login")
[[ -n "$OWNER_TOKEN" && "$OWNER_TOKEN" != "null" ]] || { echo "ERROR: owner login failed"; echo "$owner_login"; exit 1; }
AUTH=(-H "Authorization: Bearer $OWNER_TOKEN")

tournament_payload=$(jq -nc \
  --arg name "Analytics Test Tournament $SUFFIX" \
  '{name:$name,format:"T20",overs:20,location:"Pratapnagar",startDate:"2026-08-20"}')
tournament=$(json -X POST "$BASE_URL/tournaments" "${AUTH[@]}" -d "$tournament_payload")
TOURNAMENT_ID=$(jq -r '.id' <<<"$tournament")
[[ -n "$TOURNAMENT_ID" && "$TOURNAMENT_ID" != "null" ]] || { echo "ERROR: tournament creation failed"; echo "$tournament"; exit 1; }
echo "Tournament: $TOURNAMENT_ID"

declare -a TEAM_IDS
declare -a TEAM_NAMES=("Rahul Warriors" "Pratap Kings" "Test Strikers" "Digital Challengers")

for name in "${TEAM_NAMES[@]}"; do
  team_payload=$(jq -nc --arg name "$name $SUFFIX" '{name:$name,city:"Pratapnagar"}')
  team=$(json -X POST "$BASE_URL/teams" "${AUTH[@]}" -d "$team_payload")
  team_id=$(jq -r '.id' <<<"$team")
  [[ -n "$team_id" && "$team_id" != "null" ]] || { echo "ERROR: team creation failed for $name"; echo "$team"; exit 1; }
  TEAM_IDS+=("$team_id")
  echo "Team: $name -> $team_id"
done

for team_id in "${TEAM_IDS[@]}"; do
  json -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/teams/$team_id" "${AUTH[@]}" -d '{}' >/dev/null
done

fixtures=$(json -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/generate" "${AUTH[@]}" -d '{}')
generated=$(jq -r '.generated // 0' <<<"$fixtures")
[[ "$generated" == "6" ]] || { echo "ERROR: expected 6 generated fixtures, got $generated"; echo "$fixtures"; exit 1; }
echo "Fixtures generated: $generated"

docker exec -i "$POSTGRES_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" <<SQL
BEGIN;

-- Remove any aggregate data if this exact tournament is ever seeded again.
DELETE FROM deliveries
 WHERE innings_id IN (
   SELECT i.id FROM innings i
   JOIN tournament_matches tm ON tm.match_id = i.match_id
   WHERE tm.tournament_id = '$TOURNAMENT_ID'::uuid
 );
DELETE FROM partnerships
 WHERE innings_id IN (
   SELECT i.id FROM innings i
   JOIN tournament_matches tm ON tm.match_id = i.match_id
   WHERE tm.tournament_id = '$TOURNAMENT_ID'::uuid
 );
DELETE FROM innings
 WHERE match_id IN (
   SELECT match_id FROM tournament_matches WHERE tournament_id = '$TOURNAMENT_ID'::uuid
 );

-- First innings: Team A bats first in every generated fixture.
INSERT INTO innings (
    id, match_id, innings_number, batting_team_id, bowling_team_id,
    total_runs, wickets, legal_balls, total_overs, status, target_runs,
    current_over, current_ball, declared, is_super_over
)
SELECT
    gen_random_uuid(), tm.match_id, 1, m.team_a_id, m.team_b_id,
    CASE tm.fixture_number
        WHEN 1 THEN 150 WHEN 2 THEN 130 WHEN 3 THEN 160
        WHEN 4 THEN 125 WHEN 5 THEN 155 WHEN 6 THEN 165
    END,
    CASE tm.fixture_number
        WHEN 1 THEN 4 WHEN 2 THEN 6 WHEN 3 THEN 4
        WHEN 4 THEN 6 WHEN 5 THEN 4 WHEN 6 THEN 6
    END,
    120, 20, 'COMPLETED', NULL, 20, 0, FALSE, FALSE
FROM tournament_matches tm
JOIN matches m ON m.id = tm.match_id
WHERE tm.tournament_id = '$TOURNAMENT_ID'::uuid;

-- Second innings. Fixtures 1/3/5 are won by Team A; 2/4/6 by Team B.
INSERT INTO innings (
    id, match_id, innings_number, batting_team_id, bowling_team_id,
    total_runs, wickets, legal_balls, total_overs, status, target_runs,
    current_over, current_ball, declared, is_super_over
)
SELECT
    gen_random_uuid(), tm.match_id, 2, m.team_b_id, m.team_a_id,
    CASE tm.fixture_number
        WHEN 1 THEN 120 WHEN 2 THEN 145 WHEN 3 THEN 140
        WHEN 4 THEN 135 WHEN 5 THEN 100 WHEN 6 THEN 170
    END,
    CASE tm.fixture_number
        WHEN 1 THEN 6 WHEN 2 THEN 4 WHEN 3 THEN 6
        WHEN 4 THEN 4 WHEN 5 THEN 6 WHEN 6 THEN 4
    END,
    120, 20, 'COMPLETED',
    CASE tm.fixture_number
        WHEN 1 THEN 151 WHEN 2 THEN 131 WHEN 3 THEN 161
        WHEN 4 THEN 126 WHEN 5 THEN 156 WHEN 6 THEN 166
    END,
    20, 0, FALSE, FALSE
FROM tournament_matches tm
JOIN matches m ON m.id = tm.match_id
WHERE tm.tournament_id = '$TOURNAMENT_ID'::uuid;

UPDATE matches m
SET status = 'COMPLETED',
    winning_team_id = CASE
        WHEN tm.fixture_number IN (1,3,5) THEN m.team_a_id
        ELSE m.team_b_id
    END,
    result_type = 'WIN',
    result_text = CASE
        WHEN tm.fixture_number IN (1,3,5) THEN
            (SELECT name FROM teams WHERE id = m.team_a_id) || ' won by ' ||
            (i1.total_runs - i2.total_runs) || ' runs'
        ELSE
            (SELECT name FROM teams WHERE id = m.team_b_id) || ' won by ' ||
            (i2.total_runs - i1.total_runs) || ' runs'
    END,
    completed_at = COALESCE(m.completed_at, now()),
    current_innings_id = NULL
FROM tournament_matches tm
JOIN innings i1 ON i1.match_id = m.id AND i1.innings_number = 1
JOIN innings i2 ON i2.match_id = m.id AND i2.innings_number = 2
WHERE tm.tournament_id = '$TOURNAMENT_ID'::uuid
  AND tm.match_id = m.id;

COMMIT;
SQL

echo
echo "=== ANALYTICS TEST DATA READY ==="
echo "Tournament ID : $TOURNAMENT_ID"
echo "Tournament    : http://localhost:4200/tournaments/$TOURNAMENT_ID"
echo "Analytics     : http://localhost:4200/tournaments/$TOURNAMENT_ID/analytics"
echo "Qualification : http://localhost:4200/tournaments/$TOURNAMENT_ID/qualification"
echo "Schedule      : http://localhost:4200/tournaments/$TOURNAMENT_ID/schedule"
echo "Fixtures      : 6 completed"
echo "Login         : $OWNER_EMAIL / $OWNER_PASSWORD"
echo
echo "NOTE: Aggregate test data only; no player or ball-by-ball data is created."
