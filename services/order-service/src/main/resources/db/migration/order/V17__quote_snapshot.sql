CREATE TABLE IF NOT EXISTS quote_snapshot (
    quote_id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    product_id VARCHAR(50) NOT NULL,
    line VARCHAR(20),
    trip_duration_days INTEGER,
    coverage_amount_vnd BIGINT,
    deductible_vnd BIGINT,
    profile JSONB,
    final_premium_vnd BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_quote_snapshot_customer_id ON quote_snapshot(customer_id);
CREATE INDEX IF NOT EXISTS idx_quote_snapshot_expires_at ON quote_snapshot(expires_at);
