CREATE TABLE IF NOT EXISTS claim_policy_projection (
    policy_id UUID PRIMARY KEY,
    order_id UUID,
    quote_id UUID,
    customer_id UUID NOT NULL,
    product_id VARCHAR(50),
    line VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    policy_effective_date TIMESTAMPTZ,
    policy_expiration_date TIMESTAMPTZ,
    final_premium_vnd BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS claim_exposure_segment_projection (
    segment_id UUID PRIMARY KEY,
    policy_id UUID NOT NULL,
    exposure_segment_seq INTEGER NOT NULL,
    segment_start TIMESTAMPTZ NOT NULL,
    segment_end TIMESTAMPTZ NOT NULL,
    earned_exposure_years DOUBLE PRECISION NOT NULL DEFAULT 0,
    coverage_amount_vnd BIGINT NOT NULL DEFAULT 0,
    deductible_vnd BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (policy_id, exposure_segment_seq)
);

CREATE INDEX IF NOT EXISTS idx_claim_policy_projection_customer_id ON claim_policy_projection(customer_id);
CREATE INDEX IF NOT EXISTS idx_claim_exposure_segment_projection_policy_id ON claim_exposure_segment_projection(policy_id);
