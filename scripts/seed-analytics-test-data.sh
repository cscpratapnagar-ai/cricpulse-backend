#!/usr/bin/env bash
set -euo pipefail

# Creates a complete local tournament dataset for Analytics and Qualification UI testing.
# It uses the public tournament/team/fixture APIs, then seeds only the score aggregates
# required by the existing points-table implementation. It intentionally does not create
# players or call an unverified team-members endpoint.

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
  echo "ERROR: PostgreSQL container '$POSTGRES_CONTAINER' was not found."
  echo "Set POSTGRES_CONTAINER to your actual container name."
  exit 1
}

json() {
  curl -sS --fail-with-body -H "Content-Type: application/json" "$@"
}

echo "== CricPulse Analytics / Qualification Test Data Seeder =="
echo "API: $BASE_URL"
echo "PostgreSQL: $POSTGRES_CONTAINER / $DB_NAME"
echo

# The test owner is expected to exist in a local database. If it does not, create it.
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
[[ -n "$OWNER_TOKEN" && "$OWNER_TOKEN" != "null" ]] || {
  echo "ERROR: owner login failed"
  echo "$owner_login"
  exit 1
}
AUTH=(-H "Authorization: Bearer $OWNER_TOKEN")

tournament_payload=$(jq -nc \
  --arg name "Analytics Test Tournament $SUFFIX" \
  '{name:$name,format:"T20",overs:20,location:"Pratapnagar",startDate:"2026-08-20"}')
tournament=$(json -X POST "$BASE_URL/tournaments" "${AUTH[@]}" -d "$tournament_payload")
TOURNAMENT_ID=$(jq -r '.id' <<<"$tournament")
[[ -n "$TOURNAMENT_ID" && "$TOURNAMENT_ID" != "null" ]] || {
  echo "ERROR: tournament creation failed"
  echo "$tournament"
  exit 1
}
echo "Tournament: $TOURNAMENT_ID"

declare -a TEAM_IDS
declare -a TEAM_NAMES=("Rahul Warriors" "Pratap Kings" "Test Strikers" "Digital Challengers")

for name in "${TEAM_NAMES[@]}"; do
  team_payload=$(jq -nc \
    --arg name "$name $SUFFIX" \
    '{name:$name,city:"Pratapnagar"}')
  team=$(json -X POST "$BASE_URL/teams" "${AUTH[@]}" -d "$team_payload")
  team_id=$(jq -r '.id' <<<"$team")
  [[ -n "$team_id" && "$team_id" != "null" ]] || {
    echo "ERROR: team creation failed for $name"
    echo "$team"
    exit 1
  }
  TEAM_IDS+=("$team_id")
  echo "Team: $name -> $team_id"
done

# Register all four teams in the tournament and generate the six round-robin fixtures.
for team_id in "${TEAM_IDS[@]}"; do
  json -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/teams/$team_id" "${AUTH[@]}" -d '{}' >/dev/null
done

fixtures=$(json -X POST "$BASE_URL/tournaments/$TOURNAMENT_ID/fixtures/generate" "${AUTH[@]}" -d '{}')
generated=$(jq -r '.generated // 0' <<<"$fixtures")
[[ "$generated" == "6" ]] || {
  echo "ERROR: expected 6 generated fixtures, got $generated"
  echo "$fixtures"
  exit 1
}
echo "Fixtures generated: $generated"

# Seed two completed innings per fixture. The points-table endpoint derives
# played/wins/losses/points/NRR directly from these rows.
docker exec -i "$POSTGRES_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" <<SQL
BEGIN;

DO \\\$\\$
DECLARE
    tid uuid := '$TOURNAMENT_ID'::uuid;
    rec record;
    winner_runs integer;
    loser_runs integer;
    first_runs integer;
    second_runs integer;
    first_wickets integer;
    second_wickets integer;
    winning_team uuid;
BEGIN
    IF (SELECT COUNT(*) FROM tournament_matches WHERE tournament_id = tid) <> 6 THEN
        RAISE EXCEPTION 'Expected exactly 6 fixtures for tournament %', tid;
    END IF;

    -- This makes the script safe to rerun for the same tournament ID.
    DELETE FROM deliveries
     WHERE innings_id IN (
       SELECT i.id
       FROM innings i
       JOIN tournament_matches tm ON tm.match_id = i.match_id
       WHERE tm.tournament_id = tid
     );

    DELETE FROM partnerships
     WHERE innings_id IN (
       SELECT i.id
       FROM innings i
       JOIN tournament_matches tm ON tm.match_id = i.match_id
       WHERE tm.tournament_id = tid
     );

    DELETE FROM innings
     WHERE match_id IN (
       SELECT match_id FROM tournament_matches WHERE tournament_id = tid
     );

    FOR rec IN
        SELECT tm.match_id, tm.fixture_number, m.team_a_id, m.team_b_id
        FROM tournament_matches tm
        JOIN matches m ON m.id = tm.match_id
        WHERE tm.tournament_id = tid
        ORDER BY tm.fixture_number
    LOOP
        -- Fixtures 1, 3 and 5 are won by Team A; 2, 4 and 6 by Team B.
        IF rec.fixture_number IN (1, 3, 5) THEN
            winning_team := rec.team_a_id;
            winner_runs := CASE rec.fixture_number
                WHEN 1 THEN 150
                WHEN 3 THEN 160
                ELSE 155
            END;
            loser_runs := CASE rec.fixture_number
                WHEN 1 THEN 120
                WHEN 3 THEN 140
                ELSE 100
            END;
        ELSE
            winning_team := rec.team_b_id;
            winner_runs := CASE rec.fixture_number
                WHEN 2 THEN 145
                WHEN 4 THEN 135
                ELSE 170
            END;
            loser_runs := CASE rec.fixture_number
                WHEN 2 THEN 130
                WHEN 4 THEN 125
                ELSE 165
            END;
        END IF;

        IF winning_team = rec.team_a_id THEN
            first_runs := winner_runs;
            second_runs := loser_runs;
            first_wickets := 4;
            second_wickets := 6;
        ELSE
            first_runs := loser_runs;
            second_runs := winner_runs;
            first_wickets := 6;
            second_wickets := 4;
        END IF;

        INSERT INTO innings (
            id, match_id, innings_number, batting_team_id, bowling_team_id,
            total_runs, wickets, legal_balls, total_overs, status, target_runs,
            current_over, current_ball, declared, is_super_over
        ) VALUES (
            gen_random_uuid(), rec.match_id, 1, rec.team_a_id, rec.team_b_id,
            first_runs, first_wickets, 120, 20, 'COMPLETED', NULL,
            20, 0, FALSE, FALSE
        );

        INSERT INTO innings (
            id, match_id, innings_number, batting_team_id, bowling_team_id,
            total_runs, wickets, legal_balls, total_overs, status, target_runs,
            current_over, current_ball, declared, is_super_over
        ) VALUES (
            gen_random_uuid(), rec.match_id, 2, rec.team_b_id, rec.team_a_id,
            second_runs, second_wickets, 120, 20, 'COMPLETED', first_runs + 1,
            20, 0, FALSE, FALSE
        );

        UPDATE matches
           SET status = 'COMPLETED',
               winning_team_id = winning_team,
               result_type = 'WIN',
               result_text = CASE
                   WHEN winning_team = rec.team_a_id
                     THEN (SELECT name FROM teams WHERE id = rec.team_a_id) || ' won by ' || (first_runs - second_runs) || ' runs'
                   ELSE (SELECT name FROM teams WHERE id = rec.team_b_id) || ' won by ' || (second_runs - first_runs) || ' runs'
               END,
               completed_at = COALESCE(completed_at, now()),
               current_innings_id = NULL
         WHERE id = rec.match_id;
    END LOOP;
END \\\$\\$;

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
echo "NOTE: This dataset is intentionally aggregate-only for Analytics/Qualification UI testing."
echo "It does not simulate player-level or ball-by-ball scoring events."
