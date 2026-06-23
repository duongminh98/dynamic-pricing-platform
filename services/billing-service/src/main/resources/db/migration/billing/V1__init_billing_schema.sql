-- V1__init_billing_schema.sql
-- Billing schema per design 5.6

CREATE TABLE invoice (
    invoice_id  UUID        PRIMARY KEY,
    order_id    UUID        NOT NULL,
    policy_id   UUID,
    amount_vnd  BIGINT      NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'unpaid',
    paid_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_invoice_status CHECK (status IN ('unpaid','paid'))
);

CREATE INDEX idx_invoice_policy ON invoice (policy_id);
CREATE INDEX idx_invoice_order ON invoice (order_id);

CREATE TABLE adjustment (
    adjustment_id UUID        PRIMARY KEY,
    policy_id     UUID        NOT NULL,
    type          VARCHAR(20) NOT NULL,
    amount_vnd    BIGINT      NOT NULL,
    reason        VARCHAR(20) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_adjustment_type CHECK (type IN ('additional_charge','refund')),
    CONSTRAINT chk_adjustment_reason CHECK (reason IN ('endorsement','cancellation'))
);

CREATE INDEX idx_adjustment_policy ON adjustment (policy_id);
