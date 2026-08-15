-- V15 - Allow multiple physical deliveries for the same legal ball number.
-- Wides and no-balls do not advance the legal ball counter, so they may
-- legitimately share the same over/ball label. sequence_number is the
-- authoritative delivery ordering key.
ALTER TABLE deliveries
    DROP CONSTRAINT IF EXISTS deliveries_innings_id_over_number_ball_number_key;

CREATE INDEX IF NOT EXISTS idx_deliveries_innings_sequence_order
    ON deliveries(innings_id, sequence_number, created_at);
