-- V29 - Fielding attribution for delivery events
-- Keep the projected delivery and immutable event ledger aligned.
ALTER TABLE delivery_events
    ADD COLUMN wicket_fielder_id UUID REFERENCES players(id);

CREATE INDEX idx_deliveries_match_fielding
    ON deliveries(innings_id, wicket_fielder_id, wicket_type)
    WHERE wicket_fielder_id IS NOT NULL;

COMMENT ON COLUMN deliveries.wicket_fielder_id IS
    'Bowling-side fielder credited with a recorded caught, run-out, or stumped dismissal';

COMMENT ON COLUMN delivery_events.wicket_fielder_id IS
    'Fielder attribution captured in the immutable scoring event';
