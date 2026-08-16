-- Qualification Preview test data
--
-- IMPORTANT: This script is intentionally explicit and test-only. It targets the
-- tournament created by scripts/seed-qualification-test-data.sh and converts its
-- six generated fixtures into deterministic completed matches with two innings.
-- Run only against a disposable/local CricPulse database.
--
-- Tournament:
-- 3597ebf7-f549-4398-9832-52e5479de73f
--
-- This creates a clean points/NRR scenario for Qualification Preview without
-- changing application code or production scoring logic.

BEGIN;

DO $$
DECLARE
    tid uuid := '3597ebf7-f549-4398-9832-52e5479de73f';
    rec record;
    m_count integer;
    fixture_no integer;
    team_a uuid;
    team_b uuid;
    ia_id uuid;
    ib_id uuid;
    winner_runs integer;
    loser_runs integer;
    team_a_wins boolean;
BEGIN
    SELECT COUNT(*) INTO m_count FROM tournament_matches WHERE tournament_id = tid;
    IF m_count <> 6 THEN
        RAISE EXCEPTION 'Expected exactly 6 generated fixtures for tournament %, found %', tid, m_count;
    END IF;

    -- Clear only scoring/result rows for these six local test matches.
    DELETE FROM deliveries
      WHERE innings_id IN (SELECT id FROM innings WHERE match_id IN
          (SELECT match_id FROM tournament_matches WHERE tournament_id = tid));
    DELETE FROM partnerships
      WHERE innings_id IN (SELECT id FROM innings WHERE match_id IN
          (SELECT match_id FROM tournament_matches WHERE tournament_id = tid));
    DELETE FROM innings
      WHERE match_id IN (SELECT match_id FROM tournament_matches WHERE tournament_id = tid);

    FOR rec IN
        SELECT tm.match_id, tm.fixture_number, m.team_a_id, m.team_b_id
        FROM tournament_matches tm
        JOIN matches m ON m.id = tm.match_id
        WHERE tm.tournament_id = tid
        ORDER BY tm.fixture_number
    LOOP
        fixture_no := rec.fixture_number;
        team_a := rec.team_a_id;
        team_b := rec.team_b_id;
        team_a_wins := fixture_no IN (1, 3, 6);

        -- Deterministic scores. The deliberately different margins create
        -- different NRR values while preserving a clear points ranking.
        CASE fixture_no
            WHEN 1 THEN winner_runs := 150; loser_runs := 120;
            WHEN 2 THEN winner_runs := 145; loser_runs := 130;
            WHEN 3 THEN winner_runs := 160; loser_runs := 140;
            WHEN 4 THEN winner_runs := 135; loser_runs := 125;
            WHEN 5 THEN winner_runs := 155; loser_runs := 100;
            WHEN 6 THEN winner_runs := 170; loser_runs := 165;
            ELSE RAISE EXCEPTION 'Unexpected fixture number %', fixture_no;
        END CASE;

        ia_id := gen_random_uuid();
        ib_id := gen_random_uuid();

        INSERT INTO innings (
            id, match_id, innings_number, batting_team_id, bowling_team_id,
            total_runs, wickets, legal_balls, total_overs, status, target_runs,
            current_over, current_ball, declared, is_super_over
        ) VALUES (
            ia_id, rec.match_id, 1,
            team_a, team_b,
            CASE WHEN team_a_wins THEN winner_runs ELSE loser_runs END,
            4, 120, 20, 'COMPLETED', NULL,
            20, 0, FALSE, FALSE
        );

        INSERT INTO innings (
            id, match_id, innings_number, batting_team_id, bowling_team_id,
            total_runs, wickets, legal_balls, total_overs, status, target_runs,
            current_over, current_ball, declared, is_super_over
        ) VALUES (
            ib_id, rec.match_id, 2,
            team_b, team_a,
            CASE WHEN team_a_wins THEN loser_runs ELSE winner_runs END,
            6, 120, 20, 'COMPLETED',
            CASE WHEN team_a_wins THEN winner_runs + 1 ELSE loser_runs + 1 END,
            20, 0, FALSE, FALSE
        );

        UPDATE matches
           SET status = 'COMPLETED',
               current_innings_id = ib_id
         WHERE id = rec.match_id;
    END LOOP;
END $$;

COMMIT;

-- Verify the resulting tournament points table through the application API:
-- GET /api/tournaments/3597ebf7-f549-4398-9832-52e5479de73f/points-table
-- GET /api/tournaments/3597ebf7-f549-4398-9832-52e5479de73f/qualification
