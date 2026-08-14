CREATE TABLE match_staff (
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(30) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, user_id, role),
    CONSTRAINT match_staff_role_ck CHECK (role IN ('SCORER', 'UMPIRE', 'COMMENTATOR', 'STREAMER'))
);

CREATE INDEX idx_match_staff_user ON match_staff(user_id);
CREATE INDEX idx_team_members_player ON team_members(player_id);

COMMENT ON TABLE match_staff IS 'Context-specific match staff assignments. A user may hold multiple staff roles on different matches.';
COMMENT ON COLUMN team_members.role IS 'Context-specific team role: PLAYER, CAPTAIN, VICE_CAPTAIN, MANAGER, OWNER.';
