#!/usr/bin/env bash
set -euo pipefail

# Creates a complete local tournament test dataset and then marks all six
a generated fixtures as completed with deterministic innings/result data.
# This is intended for local/dev analytics and qualification testing only.

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

echo "== CricPulse Analytics Dashboard Test Data Seeder =="
echo "API: $BASE_URL"
echo "PostgreSQL: $POSTGRES_CONTAINER / $DB_NAME"
echo

owner_register=$(jq -nc \
  --arg fullName "$OWNER_NAME" \
  --arg email "$OWNER_EMAIL" \
  --arg phone "$OWNER_PHONE" \
  --arg password "$OWNER_PASSWORD" \
  '{fullName:$fullName,email:$email,phone:$phone,password:$password}')

if ! json -X POST "$BASE_URL/users" -d "$owner_register" >/tmp/cricpulse_analytics_owner.json 2>/tmp/cricpulse_analytics_owner.err; then
  echo "Owner registration skipped (account may already exist)."
fi

owner_login=$(json -X POST "$BASE_URL/auth/login" -d "$(jq -nc --arg email "$OWNER_EMAIL" --arg password "$OWNER_PASSWORD" '{email:$email,password:$password}')")
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
[[ -n "$TOURNAMENT_ID" && "$TOURNAMENT_ID" != "null" ]] || { echo "$tournament"; exit 1; }
echo "Tournament: $TOURNAMENT_ID"

declare -a TEAM_IDS
declare -a TEAM_NAMES=("Rahul Warriors" "Pratap Kings" "Test Strikers" "Digital Challengers")

for name in "${TEAM_NAMES[@]}"; do
  team_payload=$(jq -nc --arg name "$name $SUFFIX" '{name:$name,city:"Pratapnagar"}')
  team=$(json -X POST "$BASE_URL/teams" "${AUTH[@]}" -d "$team_payload")
  team_id=$(jq -r '.id' <<<"$team")
  [[ -n "$team_id" && "$team_id" != "null" ]] || { echo "$team"; exit 1; }
  TEAM_IDS+=("$team_id")
  echo "Team: $name -> $team_id"
done

for team_index in 0 1 2 3; do
  team_id="${TEAM_IDS[$team_index]}"
  team_name="${TEAM_NAMES[$team_index]}"

  for player_index in $(seq 1 11); do
    code=$(printf '%02d' "$player_index")
    email_prefix=$(echo "$team_name" | tr '[:upper:] ' '[:lower:]_' | tr -cd '[:alnum:]_')
    email="${email_prefix}.${SUFFIX}.${code}@example.com"
    phone="700000$(printf '%04d' $((team_index * 11 + player_index)))"
    full_name="${team_name} Player ${code}"

    register_payload=$(jq -nc \
      --arg fullName "$full_name" \
      --arg email "$email" \
      --arg phone "$phone" \
      --arg password "$OWNER_PASSWORD" \
      '{fullName:$fullName,email:$email,phone:$phone,password:$password}')

    if ! json -X POST "$BASE_URL/users" -d "$register_payload" >/dev/null 2>/tmp/cricpulse_analytics_player.err; then
      echo "Player registration skipped: $email"
    fi

    member_payload=$(jq -nc --arg email "$email" '{email:$email,role:"PLAYER"}')
    json -X POST "$BASE_URL/teams/$team_id/members" "${AUTH[@]}" -d "$member_payload" >/dev/null
  done

  echo "Added 11 players to $team_name"
done

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

# Use an unquoted heredoc so the shell substitutes the safe UUID before psql.
docker exec -i "$POSTGRES_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" <<SQL
BEGIN;

DO \$\$
DECLARE
    tid uuid := '$TOURNAMENT_ID'::uuid;
    rec record;
    ia_id uuid;
    ib_id uuid;
    winner_runs integer;
    loser_runs integer;
    team_a_wins boolean;
    first_runs integer;
    second_runs integer;
BEGIN
    IF (SELECT COUNT(*) FROM tournament_matches WHERE tournament_id = tid) <> 6 THEN
        RAISE EXCEPTION 'Expected exactly 6 fixtures for tournament %', tid;
    END IF;

    DELETE FROM deliveries
      WHERE innings_id IN (
        SELECT id FROM innings WHERE match_id IN (
          SELECT match_id FROM tournament_matches WHERE tournament_id = tid
        )
      );

    DELETE FROM partnerships
      WHERE innings_id IN (
        SELECT id FROM innings WHERE match_id IN (
          SELECT match_id FROM tournament_matches WHERE tournament_id = tid
        )
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
        team_a_wins := rec.fixture_number IN (1, 3, 5);

        CASE rec.fixture_number
            WHEN 1 THEN winner_runs := 150; loser_runs := 120;
            WHEN 2 THEN winner_runs := 145; loser_runs := 130;
            WHEN 3 THEN winner_runs := 160; loser_runs := 140;
            WHEN 4 THEN winner_runs := 135; loser_runs := 125;
            WHEN 5 THEN winner_runs := 155; loser_runs := 100;
            WHEN 6 THEN winner_runs := 170; loser_runs := 165;
            ELSE RAISE EXCEPTION 'Unexpected fixture number %', rec.fixture_number;
        END CASE;

        IF team_a_wins THEN
            first_runs := winner_runs;
            second_runs := loser_runs;
        ELSE
            first_runs := loser_runs;
            second_runs := winner_runs;
        END IF;

        ia_id := gen_random_uuid();
        ib_id := gen_random_uuid();

        INSERT INTO innings (
            id, match_id, innings_number, batting_team_id, bowling_team_id,
            total_runs, wickets, legal_balls, total_overs, status, target_runs,
            current_over, current_ball, declared, is_super_over
        ) VALUES (
            ia_id, rec.match_id, 1, rec.team_a_id, rec.team_b_id,
            first_runs, 4, 120, 20, 'COMPLETED', NULL,
            20, 0, FALSE, FALSE
        );

        INSERT INTO innings (
            id, match_id, innings_number, batting_team_id, bowling_team_id,
            total_runs, wickets, legal_balls, total_overs, status, target_runs,
            current_over, current_ball, declared, is_super_over
        ) VALUES (
            ib_id, rec.match_id, 2, rec.team_b_id, rec.team_a_id,
            second_runs, CASE WHEN second_runs >= first_runs THEN 4 ELSE 6 END,
            120, 20, 'COMPLETED', first_runs + 1,
            20, 0, FALSE, FALSE
        );

        UPDATE matches
           SET status = 'COMPLETED',
               winning_team_id = CASE WHEN second_runs > first_runs THEN rec.team_b_id ELSE rec.team_a_id END,
               result_type = 'WIN',
               result_text = CASE
                   WHEN second_runs > first_runs THEN 'Team B won by 6 wickets'
                   ELSE 'Team A won by ' || (first_runs - second_runs) || ' runs'
               END,
               completed_at = COALESCE(completed_at, now()),
               current_innings_id = NULL
         WHERE id = rec.match_id;
    END LOOP;
END \$\$;

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
echo "NOTE: This is test data for Analytics/Qualification UI."
echo "It does NOT simulate ball-by-ball scoring-engine events."
