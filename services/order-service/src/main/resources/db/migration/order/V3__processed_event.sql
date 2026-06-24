-- V3: consumer-side idempotency ledger (R6.6). Records each consumed event so a
-- redelivered InvoicePaid (same X-Event-Id) does not issue a policy twice.
CREATE TABLE IF NOT EXISTS processed_event (
    event_id     VARCHAR(64) PRIMARY KEY,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
