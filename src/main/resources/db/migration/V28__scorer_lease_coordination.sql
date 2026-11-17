CREATE TABLE scorer_leases (
    innings_id UUID PRIMARY KEY REFERENCES innings(id) ON DELETE CASCADE,
    holder_key VARCHAR(255) NOT NULL,
    lease_token UUID NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_scorer_leases_expiry ON scorer_leases(expires_at);
