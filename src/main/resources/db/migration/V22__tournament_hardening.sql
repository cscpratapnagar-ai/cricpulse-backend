-- Phase 2 tournament hardening: safe fixture metadata indexes.
CREATE INDEX IF NOT EXISTS idx_tournament_matches_stage
    ON tournament_matches(tournament_id, stage);

CREATE INDEX IF NOT EXISTS idx_tournament_matches_match
    ON tournament_matches(match_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tournament_fixture_number
    ON tournament_matches(tournament_id, fixture_number)
    WHERE fixture_number IS NOT NULL AND fixture_number > 0;
