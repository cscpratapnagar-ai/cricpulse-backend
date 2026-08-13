-- ============================================================
-- V4 - Scoring Engine Foundation
-- CricPulse Live Cricket Scoring System
-- ============================================================

-- Match-level scoring configuration.
ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS total_overs INTEGER;

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS toss_winner_team_id UUID REFERENCES teams(id);

ALTER TABLE matches
    ADD COLUMN IF NOT EXISTS toss_decision VARCHAR(20);

-- Innings runtime state. Backend will become the single source of
-- truth for striker, non-striker, bowler and current ball state.
ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS total_overs INTEGER;

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'LIVE';

ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS target_runs INTEGER;

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

-- Delivery metadata required by the real scoring engine.
ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS sequence_number INTEGER;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS legal_delivery BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS total_runs INTEGER NOT NULL DEFAULT 0;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS is_boundary BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS is_four BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS is_six BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS commentary TEXT;

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS wicket_fielder_id UUID REFERENCES players(id);

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS new_batter_id UUID REFERENCES players(id);

-- Over-level aggregate. One row represents one bowling over.
CREATE TABLE IF NOT EXISTS innings_overs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    innings_id UUID NOT NULL REFERENCES innings(id) ON DELETE CASCADE,
    over_number INTEGER NOT NULL,
    bowler_id UUID NOT NULL REFERENCES players(id),
    runs INTEGER NOT NULL DEFAULT 0,
    wickets INTEGER NOT NULL DEFAULT 0,
    legal_balls INTEGER NOT NULL DEFAULT 0,
    wides INTEGER NOT NULL DEFAULT 0,
    no_balls INTEGER NOT NULL DEFAULT 0,
    byes INTEGER NOT NULL DEFAULT 0,
    leg_byes INTEGER NOT NULL DEFAULT 0,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (innings_id, over_number)
);

CREATE INDEX IF NOT EXISTS idx_innings_overs_innings
    ON innings_overs(innings_id);

-- Per-innings batting scorecard state.
CREATE TABLE IF NOT EXISTS innings_batters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    innings_id UUID NOT NULL REFERENCES innings(id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES players(id),
    batting_position INTEGER,
    runs INTEGER NOT NULL DEFAULT 0,
    balls_faced INTEGER NOT NULL DEFAULT 0,
    fours INTEGER NOT NULL DEFAULT 0,
    sixes INTEGER NOT NULL DEFAULT 0,
    strike_rate NUMERIC(8,2) NOT NULL DEFAULT 0,
    is_out BOOLEAN NOT NULL DEFAULT FALSE,
    dismissal_type VARCHAR(30),
    dismissal_delivery_id UUID REFERENCES deliveries(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (innings_id, player_id)
);

CREATE INDEX IF NOT EXISTS idx_innings_batters_innings
    ON innings_batters(innings_id);

-- Per-innings bowling scorecard state.
CREATE TABLE IF NOT EXISTS innings_bowlers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    innings_id UUID NOT NULL REFERENCES innings(id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES players(id),
    legal_balls INTEGER NOT NULL DEFAULT 0,
    runs_conceded INTEGER NOT NULL DEFAULT 0,
    wickets INTEGER NOT NULL DEFAULT 0,
    wides INTEGER NOT NULL DEFAULT 0,
    no_balls INTEGER NOT NULL DEFAULT 0,
    economy NUMERIC(8,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (innings_id, player_id)
);

CREATE INDEX IF NOT EXISTS idx_innings_bowlers_innings
    ON innings_bowlers(innings_id);

-- Partnership tracking.
CREATE TABLE IF NOT EXISTS partnerships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    innings_id UUID NOT NULL REFERENCES innings(id) ON DELETE CASCADE,
    wicket_number INTEGER NOT NULL,
    batter_one_id UUID NOT NULL REFERENCES players(id),
    batter_two_id UUID NOT NULL REFERENCES players(id),
    runs INTEGER NOT NULL DEFAULT 0,
    balls INTEGER NOT NULL DEFAULT 0,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (innings_id, wicket_number)
);

CREATE INDEX IF NOT EXISTS idx_partnerships_innings
    ON partnerships(innings_id);

-- Fall-of-wickets history.
CREATE TABLE IF NOT EXISTS fall_of_wickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    innings_id UUID NOT NULL REFERENCES innings(id) ON DELETE CASCADE,
    wicket_number INTEGER NOT NULL,
    player_id UUID NOT NULL REFERENCES players(id),
    runs INTEGER NOT NULL,
    over_number INTEGER NOT NULL,
    ball_number INTEGER NOT NULL,
    delivery_id UUID REFERENCES deliveries(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (innings_id, wicket_number)
);

CREATE INDEX IF NOT EXISTS idx_fow_innings
    ON fall_of_wickets(innings_id);

-- Delivery lookup indexes.
CREATE INDEX IF NOT EXISTS idx_deliveries_innings_sequence
    ON deliveries(innings_id, sequence_number);

CREATE INDEX IF NOT EXISTS idx_deliveries_innings_over_ball
    ON deliveries(innings_id, over_number, ball_number);

-- Basic data integrity for the scoring layer.
ALTER TABLE deliveries
    ADD CONSTRAINT chk_delivery_bat_runs
    CHECK (bat_runs >= 0 AND bat_runs <= 6);

ALTER TABLE deliveries
    ADD CONSTRAINT chk_delivery_extra_runs
    CHECK (extra_runs >= 0);

ALTER TABLE deliveries
    ADD CONSTRAINT chk_delivery_total_runs
    CHECK (total_runs >= 0);

ALTER TABLE innings
    ADD CONSTRAINT chk_innings_wickets
    CHECK (wickets >= 0);

ALTER TABLE innings
    ADD CONSTRAINT chk_innings_runs
    CHECK (total_runs >= 0);

COMMENT ON TABLE innings_overs IS
    'Over-level scoring summary for each innings';

COMMENT ON TABLE innings_batters IS
    'Per-innings batting statistics';

COMMENT ON TABLE innings_bowlers IS
    'Per-innings bowling statistics';

COMMENT ON TABLE partnerships IS
    'Batting partnerships for an innings';

COMMENT ON TABLE fall_of_wickets IS
    'Fall-of-wicket history for an innings';
