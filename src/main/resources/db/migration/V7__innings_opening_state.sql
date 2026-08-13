-- ============================================================
-- V7 - Innings opening player state
-- CricPulse Live Cricket System
-- ============================================================

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS striker_id UUID REFERENCES players(id);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS non_striker_id UUID REFERENCES players(id);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS current_bowler_id UUID REFERENCES players(id);

CREATE INDEX IF NOT EXISTS idx_innings_striker
    ON innings(striker_id);

CREATE INDEX IF NOT EXISTS idx_innings_non_striker
    ON innings(non_striker_id);

CREATE INDEX IF NOT EXISTS idx_innings_current_bowler
    ON innings(current_bowler_id);
