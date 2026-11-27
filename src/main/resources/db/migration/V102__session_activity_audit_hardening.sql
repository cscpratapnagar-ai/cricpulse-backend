CREATE TABLE user_session_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('LOGIN', 'LOGOUT', 'SESSION_REVOKED', 'LOGOUT_ALL')),
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX user_session_audit_user_time_idx
    ON user_session_audit (user_id, occurred_at DESC);

CREATE INDEX user_session_audit_session_idx
    ON user_session_audit (session_id, occurred_at DESC);
