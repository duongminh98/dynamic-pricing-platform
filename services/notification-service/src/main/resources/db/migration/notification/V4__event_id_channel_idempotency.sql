-- V4: extend idempotency key to (event_id, channel) so the same event can
-- legitimately produce both an in_app and an email notification, while each
-- channel remains individually idempotent (R7.2, R7.7, task 20.25).

DROP INDEX IF EXISTS uq_notification_event_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_event_id_channel
    ON notification (event_id, channel)
    WHERE event_id IS NOT NULL;
