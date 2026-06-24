-- V3: VNPAY payment table (task 21.1, R33.1/R33.2).
-- Tracks each VNPAY payment attempt for an invoice. vnp_txn_ref is the unique
-- transaction reference sent to VNPAY (1:1 per payment attempt). The InvoicePaid
-- event contract is unchanged -- VNPAY only replaces the "confirm payment" step.

CREATE TABLE vnpay_payment (
    payment_id          UUID        PRIMARY KEY,
    invoice_id          UUID        NOT NULL REFERENCES invoice (invoice_id),
    vnp_txn_ref         VARCHAR(64) NOT NULL UNIQUE,
    amount_vnd          BIGINT      NOT NULL,
    vnp_transaction_no  VARCHAR(64),
    status              VARCHAR(10) NOT NULL DEFAULT 'pending',
    vnp_response_code   VARCHAR(10),
    vnp_bank_code       VARCHAR(20),
    raw_return          JSONB,
    raw_ipn             JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_vnpay_status CHECK (status IN ('pending','success','failed'))
);

CREATE INDEX idx_vnpay_invoice ON vnpay_payment (invoice_id);
CREATE INDEX idx_vnpay_txn_ref ON vnpay_payment (vnp_txn_ref);
