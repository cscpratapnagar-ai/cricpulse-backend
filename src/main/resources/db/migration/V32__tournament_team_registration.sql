ALTER TABLE tournaments
    ADD COLUMN IF NOT EXISTS registration_open BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS registration_deadline TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS tournament_team_registrations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    team_name VARCHAR(120) NOT NULL,
    city VARCHAR(120),
    contact_name VARCHAR(120) NOT NULL,
    contact_email VARCHAR(254) NOT NULL,
    contact_phone VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    review_note VARCHAR(500),
    team_id UUID REFERENCES teams(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by UUID REFERENCES users(id),
    CONSTRAINT uq_tournament_registration_team_name UNIQUE (tournament_id, team_name)
);

CREATE TABLE IF NOT EXISTS tournament_team_registration_players (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id UUID NOT NULL REFERENCES tournament_team_registrations(id) ON DELETE CASCADE,
    full_name VARCHAR(120) NOT NULL,
    jersey_number INTEGER CHECK (jersey_number BETWEEN 0 AND 999),
    batting_style VARCHAR(24),
    bowling_style VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_registration_jersey UNIQUE (registration_id, jersey_number)
);

CREATE INDEX IF NOT EXISTS ix_tournament_registration_review
    ON tournament_team_registrations(tournament_id, status, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tournament_registration_team_name_ci
    ON tournament_team_registrations(tournament_id, lower(team_name));
