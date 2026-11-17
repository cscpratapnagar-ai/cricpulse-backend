CREATE TABLE scoring_corrections (
    correction_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    innings_id UUID NOT NULL REFERENCES innings(id) ON DELETE CASCADE,
    correction_type VARCHAR(40) NOT NULL,
    affected_delivery_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_scoring_correction_type CHECK (correction_type IN ('DELIVERY_UNDONE'))
);

CREATE INDEX idx_scoring_corrections_innings_created
    ON scoring_corrections(innings_id, created_at DESC);
