ALTER TABLE match_players
    ADD COLUMN IF NOT EXISTS is_vice_captain BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_match_players_one_captain_per_team
    ON match_players(match_id, team_id)
    WHERE is_captain = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_match_players_one_vice_captain_per_team
    ON match_players(match_id, team_id)
    WHERE is_vice_captain = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_match_players_one_wicket_keeper_per_team
    ON match_players(match_id, team_id)
    WHERE is_wicket_keeper = TRUE;
