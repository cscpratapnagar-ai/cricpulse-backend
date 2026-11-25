ALTER TABLE payment_orders
    ADD COLUMN IF NOT EXISTS team_id UUID REFERENCES teams(id) ON DELETE RESTRICT;

ALTER TABLE user_entitlements
    ADD COLUMN IF NOT EXISTS team_id UUID REFERENCES teams(id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS ix_payment_orders_team_created
    ON payment_orders(team_id, created_at DESC)
    WHERE team_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_user_entitlements_team_active
    ON user_entitlements(team_id, package_code, expires_at)
    WHERE team_id IS NOT NULL AND revoked_at IS NULL;

ALTER TABLE payment_orders
    ADD CONSTRAINT ck_payment_order_scope
    CHECK (NOT (tournament_id IS NOT NULL AND team_id IS NOT NULL));

ALTER TABLE user_entitlements
    ADD CONSTRAINT ck_user_entitlement_scope
    CHECK (NOT (tournament_id IS NOT NULL AND team_id IS NOT NULL));
