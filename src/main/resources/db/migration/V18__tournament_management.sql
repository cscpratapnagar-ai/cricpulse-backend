CREATE TABLE tournaments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    format VARCHAR(30) NOT NULL,
    overs INTEGER NOT NULL CHECK (overs > 0),
    location VARCHAR(160),
    start_date DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    owner_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tournament_teams (
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    seed INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_id, team_id)
);

CREATE TABLE tournament_matches (
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    match_id UUID NOT NULL UNIQUE REFERENCES matches(id) ON DELETE CASCADE,
    stage VARCHAR(40) NOT NULL DEFAULT 'LEAGUE',
    fixture_number INTEGER,
    PRIMARY KEY (tournament_id, match_id)
);

CREATE INDEX idx_tournament_teams_team ON tournament_teams(team_id);
CREATE INDEX idx_tournament_matches_tournament ON tournament_matches(tournament_id);
