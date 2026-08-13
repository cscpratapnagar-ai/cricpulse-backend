CREATE TABLE delivery_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    innings_id UUID NOT NULL REFERENCES innings(id) ON DELETE CASCADE,
    sequence_no BIGINT NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    event_type VARCHAR(40) NOT NULL DEFAULT 'DELIVERY_RECORDED',
    over_number INTEGER NOT NULL,
    ball_number INTEGER NOT NULL,
    striker_id UUID NOT NULL REFERENCES players(id),
    non_striker_id UUID NOT NULL REFERENCES players(id),
    bowler_id UUID NOT NULL REFERENCES players(id),
    bat_runs INTEGER NOT NULL DEFAULT 0,
    extra_runs INTEGER NOT NULL DEFAULT 0,
    extra_type VARCHAR(20),
    wicket_type VARCHAR(30),
    dismissed_player_id UUID REFERENCES players(id),
    legal_delivery BOOLEAN NOT NULL DEFAULT TRUE,
    event_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    command_id UUID NOT NULL,
    recorded_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_delivery_event_sequence UNIQUE (innings_id, sequence_no),
    CONSTRAINT uq_delivery_event_version UNIQUE (innings_id, event_version),
    CONSTRAINT uq_delivery_event_command UNIQUE (command_id),
    CONSTRAINT chk_delivery_event_runs CHECK (bat_runs >= 0 AND extra_runs >= 0),
    CONSTRAINT chk_delivery_event_over CHECK (over_number >= 0 AND ball_number >= 1)
);

CREATE INDEX idx_delivery_events_innings_created ON delivery_events(innings_id, created_at);
CREATE INDEX idx_delivery_events_innings_over_ball ON delivery_events(innings_id, over_number, ball_number);
CREATE INDEX idx_delivery_events_payload ON delivery_events USING GIN(event_payload);
