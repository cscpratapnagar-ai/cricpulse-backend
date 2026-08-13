CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(255) UNIQUE,
    phone VARCHAR(30) UNIQUE,
    role VARCHAR(30) NOT NULL DEFAULT 'PLAYER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE teams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    city VARCHAR(120),
    owner_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE players (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    batting_style VARCHAR(30),
    bowling_style VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_members (
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    role VARCHAR(30) NOT NULL DEFAULT 'PLAYER',
    PRIMARY KEY (team_id, player_id)
);

CREATE TABLE matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    team_a_id UUID NOT NULL REFERENCES teams(id),
    team_b_id UUID NOT NULL REFERENCES teams(id),
    format VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SCHEDULED',
    scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE innings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    innings_number INTEGER NOT NULL,
    batting_team_id UUID NOT NULL REFERENCES teams(id),
    total_runs INTEGER NOT NULL DEFAULT 0,
    wickets INTEGER NOT NULL DEFAULT 0,
    legal_balls INTEGER NOT NULL DEFAULT 0,
    UNIQUE (match_id, innings_number)
);

CREATE TABLE deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    innings_id UUID NOT NULL REFERENCES innings(id) ON DELETE CASCADE,
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
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (innings_id, over_number, ball_number)
);
