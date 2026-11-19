CREATE TABLE tournament_staff (
    tournament_id UUID NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(30) NOT NULL DEFAULT 'MANAGER',
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_id, user_id, role),
    CONSTRAINT tournament_staff_role_ck CHECK (role IN ('MANAGER'))
);

CREATE INDEX idx_tournament_staff_user ON tournament_staff(user_id);

COMMENT ON TABLE tournament_staff IS
    'Delegated tournament-level access. Only the tournament owner can grant or revoke manager assignments.';
