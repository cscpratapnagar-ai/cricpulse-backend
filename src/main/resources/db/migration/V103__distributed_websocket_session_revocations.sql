CREATE TABLE websocket_session_revocations (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL,
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('SESSION_REVOKED', 'LOGOUT', 'LOGOUT_ALL')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX websocket_session_revocations_created_idx
    ON websocket_session_revocations (created_at);
