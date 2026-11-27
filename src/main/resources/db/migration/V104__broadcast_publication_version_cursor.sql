CREATE TABLE broadcast_publication_cursors (
    innings_id UUID PRIMARY KEY REFERENCES innings(id) ON DELETE CASCADE,
    published_version BIGINT NOT NULL DEFAULT -1,
    published_at TIMESTAMPTZ
);

INSERT INTO broadcast_publication_cursors (innings_id, published_version)
SELECT id, -1 FROM innings
ON CONFLICT (innings_id) DO NOTHING;
