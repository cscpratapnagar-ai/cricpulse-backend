-- Store compact sponsor logos in PostgreSQL so uploads survive container restarts and are backed up with match data.
CREATE TABLE broadcast_sponsor_assets (
    sponsor_id UUID PRIMARY KEY REFERENCES broadcast_sponsors(id) ON DELETE CASCADE,
    content_type VARCHAR(32) NOT NULL,
    image_data BYTEA NOT NULL,
    byte_size INTEGER NOT NULL CHECK (byte_size > 0 AND byte_size <= 2097152),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
