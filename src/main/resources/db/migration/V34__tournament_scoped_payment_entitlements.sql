ALTER TABLE payment_orders
    ADD COLUMN IF NOT EXISTS tournament_id UUID REFERENCES tournaments(id) ON DELETE RESTRICT;

ALTER TABLE user_entitlements
    ADD COLUMN IF NOT EXISTS tournament_id UUID REFERENCES tournaments(id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS ix_payment_orders_tournament_created
    ON payment_orders(tournament_id, created_at DESC)
    WHERE tournament_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_user_entitlements_tournament_active
    ON user_entitlements(tournament_id, package_code, expires_at)
    WHERE tournament_id IS NOT NULL AND revoked_at IS NULL;
