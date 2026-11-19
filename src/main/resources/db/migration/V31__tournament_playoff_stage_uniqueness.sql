-- Enforce one canonical fixture per playoff stage in each tournament.
-- LEAGUE and legacy generic knockout stages remain unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS uq_tournament_playoff_stage
    ON tournament_matches(tournament_id, stage)
    WHERE stage IN ('QUALIFIER_1', 'ELIMINATOR', 'QUALIFIER_2', 'FINAL');
