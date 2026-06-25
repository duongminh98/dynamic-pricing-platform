-- V5: consumer-side idempotency ledger (task 20.13, R33.4). Records each consumed
-- event so a redelivered EndorsementApplied / PolicyCancelled (same X-Event-Id)
-- does not insert a duplicate Adjustment (no duplicate charge / refund). Mirrors
-- the order-service processed_event pattern.
CREATE TABLE IF NOT EXISTS processed_event (
    event_id     VARCHAR(64)  PRIMARY KEY,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
