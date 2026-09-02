-- V19 - Monotonic authoritative state version for live-score synchronization.
-- Every scoring mutation increments this value, including undo/corrections.
ALTER TABLE innings
    ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_innings_state_version
    ON innings(id, state_version);
