-- A scorer command represents one user intent. The application already serializes
-- delivery mutations by innings row; this unique index is the database-level
-- last line of defence against duplicate command persistence.
CREATE UNIQUE INDEX IF NOT EXISTS ux_delivery_events_command_id
    ON delivery_events (command_id)
    WHERE command_id IS NOT NULL;
