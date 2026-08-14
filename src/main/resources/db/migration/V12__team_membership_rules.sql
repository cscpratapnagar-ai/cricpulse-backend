-- Team membership role integrity.
-- A team may have many PLAYERS, but only one OWNER, MANAGER, CAPTAIN and VICE_CAPTAIN.
CREATE UNIQUE INDEX IF NOT EXISTS ux_team_members_owner
    ON team_members(team_id) WHERE role = 'OWNER';

CREATE UNIQUE INDEX IF NOT EXISTS ux_team_members_manager
    ON team_members(team_id) WHERE role = 'MANAGER';

CREATE UNIQUE INDEX IF NOT EXISTS ux_team_members_captain
    ON team_members(team_id) WHERE role = 'CAPTAIN';

CREATE UNIQUE INDEX IF NOT EXISTS ux_team_members_vice_captain
    ON team_members(team_id) WHERE role = 'VICE_CAPTAIN';

CREATE INDEX IF NOT EXISTS ix_team_members_player ON team_members(player_id);
CREATE INDEX IF NOT EXISTS ix_team_members_team ON team_members(team_id);
