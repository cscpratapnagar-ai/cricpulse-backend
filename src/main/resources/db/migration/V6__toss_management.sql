ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS toss_winner_team_id UUID REFERENCES teams(id);

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS toss_decision VARCHAR(10);

ALTER TABLE matches
    ADD CONSTRAINT chk_matches_toss_decision
    CHECK (toss_decision IS NULL OR toss_decision IN ('BAT', 'BOWL'));

CREATE INDEX IF NOT EXISTS idx_matches_toss_winner
    ON matches(toss_winner_team_id);
