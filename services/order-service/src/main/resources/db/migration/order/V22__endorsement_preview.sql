-- Persist customer endorsement preview pricing while the async pricing service computes it.
-- These rows are short-lived and separate from endorsement_request so a preview never
-- blocks a real endorsement submission or appears in admin review queues.
CREATE TABLE endorsement_preview (
    pricing_request_id    UUID        PRIMARY KEY,
    policy_id             UUID        NOT NULL REFERENCES policy (policy_id),
    customer_id           UUID        NOT NULL,
    change_set            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    effective_date        TIMESTAMPTZ NOT NULL,
    current_premium_vnd   BIGINT      NOT NULL,
    quoted_premium_vnd    BIGINT,
    coverage_amount_vnd   BIGINT      NOT NULL,
    deductible_vnd        BIGINT      NOT NULL,
    remaining_days        BIGINT      NOT NULL,
    term_days             BIGINT      NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'PRICING_PENDING',
    pricing_failed_reason VARCHAR(500),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_endorsement_preview_status
        CHECK (status IN ('PRICING_PENDING','PRICED','PRICING_FAILED'))
);

CREATE INDEX idx_endorsement_preview_policy ON endorsement_preview (policy_id, created_at DESC);
CREATE INDEX idx_endorsement_preview_created_at ON endorsement_preview (created_at);
