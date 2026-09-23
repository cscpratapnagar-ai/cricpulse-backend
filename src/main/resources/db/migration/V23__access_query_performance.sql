-- V23 - Performance indexes for authenticated match/team access queries.
-- These indexes support the security isolation queries used by the dashboard,
-- match list, scoring access checks, and Playing XI reads.

CREATE INDEX IF NOT EXISTS idx_matches_team_a
    ON matches(team_a_id);

CREATE INDEX IF NOT EXISTS idx_matches_team_b
    ON matches(team_b_id);

CREATE INDEX IF NOT EXISTS idx_matches_scheduled_created
    ON matches(scheduled_at, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_teams_owner
    ON teams(owner_id);

CREATE INDEX IF NOT EXISTS idx_team_members_team_role_player
    ON team_members(team_id, role, player_id);

CREATE INDEX IF NOT EXISTS idx_team_members_player_team
    ON team_members(player_id, team_id);
