-- Phase 2 tournament hardening: make fixture metadata and standings inputs safe.
ALTER TABLE tournament_matches
    ADD CONSTRAINT chk_tournament_match_stage
    CHECK (stage IN ('LEAGUE', 'QUALIFIER', 'ELIMINATOR', 'SEMI_FINAL', 'FINAL', 'THIRD_PLACE', 'SUPER_OVER'));

ALTER TABLE tournament_matches
    ADD CONSTRAINT chk_tournament_match_fixture_number
    CHECK (fixture_number IS NULL OR fixture_number > 0);

CREATE UNIQUE INDEX uq_tournament_fixture_number
    ON tournament_matches(tournament_id, fixture_number)
    WHERE fixture_number IS NOT NULL;

CREATE INDEX idx_tournament_matches_stage
    ON tournament_matches(tournament_id, stage);

CREATE INDEX idx_tournament_matches_match
    ON tournament_matches(match_id);
