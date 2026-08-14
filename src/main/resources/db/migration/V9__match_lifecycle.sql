-- ============================================================
-- V9 - Match lifecycle state
-- CricPulse Live Cricket System
-- ============================================================

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS result_type VARCHAR(30);

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS result_text VARCHAR(255);

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS abandoned_reason VARCHAR(255);

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS current_innings_id UUID REFERENCES innings(id);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS bowling_team_id UUID REFERENCES teams(id);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS declared BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS is_super_over BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_matches_status
    ON matches(status);

CREATE INDEX IF NOT EXISTS idx_matches_current_innings
    ON matches(current_innings_id);

CREATE INDEX IF NOT EXISTS idx_innings_match_number
    ON innings(match_id, innings_number);

COMMENT ON COLUMN matches.result_type IS
    'WIN, TIE, DRAW, NO_RESULT, ABANDONED or NULL while live/scheduled';

COMMENT ON COLUMN matches.current_innings_id IS
    'Currently active innings for the live match';

COMMENT ON COLUMN innings.bowling_team_id IS
    'Team bowling during this innings';

COMMENT ON COLUMN innings.is_super_over IS
    'Marks an innings as part of a super-over sequence';
