-- V5: add read_at column for in-app notification read/unread state.
-- read_at = NULL means unread; non-NULL means read at that timestamp.
-- This is independent of status (which tracks delivery state, not read state).

ALTER TABLE notification ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_notification_customer_channel_read
    ON notification (customer_id, channel, read_at);
