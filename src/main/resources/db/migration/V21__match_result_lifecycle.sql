-- V21 - Persist the authoritative match result once the second innings finishes.
ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS winner_team_id UUID REFERENCES teams(id),
    ADD COLUMN IF NOT EXISTS result_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS result_margin INTEGER,
    ADD COLUMN IF NOT EXISTS result_summary VARCHAR(255);

ALTER TABLE matches
    ADD CONSTRAINT chk_matches_result_margin_non_negative
    CHECK (result_margin IS NULL OR result_margin >= 0);

CREATE INDEX IF NOT EXISTS idx_matches_winner_team
    ON matches(winner_team_id);
