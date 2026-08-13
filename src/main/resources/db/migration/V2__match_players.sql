CREATE TABLE match_players (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    team_id UUID NOT NULL REFERENCES teams(id),
    player_id UUID NOT NULL REFERENCES players(id),
    is_playing_xi BOOLEAN NOT NULL DEFAULT FALSE,
    is_captain BOOLEAN NOT NULL DEFAULT FALSE,
    is_wicket_keeper BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (match_id, player_id)
);

CREATE INDEX idx_match_players_match_team ON match_players(match_id, team_id);
