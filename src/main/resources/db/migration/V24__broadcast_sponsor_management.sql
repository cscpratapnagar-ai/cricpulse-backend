-- V24 - Match-scoped sponsor inventory, placements and delivery analytics.
CREATE TABLE IF NOT EXISTS broadcast_sponsors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id UUID NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    tagline VARCHAR(180),
    logo_url TEXT,
    sponsor_type VARCHAR(32) NOT NULL DEFAULT 'CUSTOM',
    display_duration_seconds INTEGER NOT NULL DEFAULT 10,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_broadcast_sponsor_type CHECK (sponsor_type IN
        ('MAIN_SPONSOR', 'OVER_SPONSOR', 'PLAYER_OF_THE_MATCH', 'BOUNDARY_SPONSOR', 'CUSTOM')),
    CONSTRAINT chk_broadcast_sponsor_duration CHECK (display_duration_seconds BETWEEN 5 AND 30)
);

CREATE INDEX IF NOT EXISTS idx_broadcast_sponsors_match_active
    ON broadcast_sponsors(match_id, active, created_at DESC);

CREATE TABLE IF NOT EXISTS broadcast_sponsor_placements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sponsor_id UUID NOT NULL REFERENCES broadcast_sponsors(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_broadcast_sponsor_event UNIQUE (sponsor_id, event_type),
    CONSTRAINT chk_broadcast_sponsor_event CHECK (event_type IN
        ('MATCH_START', 'OVER_COMPLETE', 'FOUR', 'SIX', 'WICKET', 'PLAYER_OF_THE_MATCH', 'MATCH_RESULT', 'MANUAL'))
);

CREATE INDEX IF NOT EXISTS idx_broadcast_placements_event
    ON broadcast_sponsor_placements(event_type, enabled, priority);

CREATE TABLE IF NOT EXISTS broadcast_sponsor_impressions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sponsor_id UUID NOT NULL REFERENCES broadcast_sponsors(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    session_key VARCHAR(120),
    displayed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_broadcast_impressions_sponsor_date
    ON broadcast_sponsor_impressions(sponsor_id, displayed_at DESC);
