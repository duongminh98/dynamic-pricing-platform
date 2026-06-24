-- V3: event-id based idempotency (R7.7) replacing the coarse (policy_id, type) key.
-- A policy can legitimately receive multiple events of the same type (e.g. several
-- ClaimStatusChanged), so dedup must key on the producer event_id instead.
ALTER TABLE notification DROP CONSTRAINT IF EXISTS uq_notification_policy_type;

ALTER TABLE notification ADD COLUMN IF NOT EXISTS event_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_event_id
    ON notification (event_id)
    WHERE event_id IS NOT NULL;
