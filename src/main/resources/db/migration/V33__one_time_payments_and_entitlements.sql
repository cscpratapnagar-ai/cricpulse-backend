CREATE TABLE IF NOT EXISTS payment_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    package_code VARCHAR(40) NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REFUNDED', 'CANCELLED')),
    provider VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    provider_order_id VARCHAR(120),
    provider_payment_id VARCHAR(120),
    idempotency_key VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_order_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT uq_payment_provider_order UNIQUE (provider, provider_order_id),
    CONSTRAINT uq_payment_provider_payment UNIQUE (provider, provider_payment_id)
);

CREATE INDEX IF NOT EXISTS ix_payment_orders_user_created
    ON payment_orders(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS payment_webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(32) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_webhook_event UNIQUE (provider, event_id)
);

CREATE TABLE IF NOT EXISTS user_entitlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    package_code VARCHAR(40) NOT NULL,
    source_order_id UUID REFERENCES payment_orders(id),
    activation_source VARCHAR(20) NOT NULL
        CHECK (activation_source IN ('PAYMENT', 'MANUAL')),
    activated_by UUID REFERENCES users(id),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_user_entitlement_order UNIQUE (source_order_id)
);

CREATE INDEX IF NOT EXISTS ix_user_entitlements_active
    ON user_entitlements(user_id, package_code, expires_at)
    WHERE revoked_at IS NULL;
