CREATE TABLE revoked_access_tokens (
    token_fingerprint CHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX revoked_access_tokens_expires_at_idx
    ON revoked_access_tokens (expires_at);
