-- ============================================================
-- V5 - Match and innings setup
-- ============================================================

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS toss_winner_team_id UUID REFERENCES teams(id);

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS toss_decision VARCHAR(20);

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS total_overs INTEGER;

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS winning_team_id UUID REFERENCES teams(id);

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS result_text VARCHAR(255);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS bowling_team_id UUID REFERENCES teams(id);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS total_overs INTEGER;

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS target_runs INTEGER;

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'LIVE';

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS current_over INTEGER NOT NULL DEFAULT 0;

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS current_ball INTEGER NOT NULL DEFAULT 0;

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS striker_id UUID REFERENCES players(id);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS non_striker_id UUID REFERENCES players(id);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS current_bowler_id UUID REFERENCES players(id);

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS declared BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS super_over BOOLEAN NOT NULL DEFAULT FALSE;

-- A non-legal delivery does not consume a ball. Ball identity therefore
-- belongs to the delivery sequence, not the over/ball pair.
ALTER TABLE deliveries
    DROP CONSTRAINT IF EXISTS deliveries_innings_id_over_number_ball_number_key;

CREATE INDEX IF NOT EXISTS idx_deliveries_innings_over_ball_sequence
    ON deliveries(innings_id, over_number, ball_number, sequence_number);

CREATE INDEX IF NOT EXISTS idx_innings_match_number
    ON innings(match_id, innings_number);
