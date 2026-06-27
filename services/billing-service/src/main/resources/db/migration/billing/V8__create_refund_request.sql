CREATE TABLE refund_request (
    refund_id        UUID PRIMARY KEY,
    policy_id        UUID NOT NULL,
    customer_id      UUID NOT NULL,
    credit_id        UUID,
    amount_vnd       BIGINT NOT NULL,
    status           VARCHAR(20) NOT NULL,
    payment_reference VARCHAR(100),
    note             VARCHAR(500),
    requested_at     TIMESTAMPTZ NOT NULL,
    completed_by     VARCHAR(36),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_refund_status ON refund_request (status);
CREATE INDEX idx_refund_policy ON refund_request (policy_id);
