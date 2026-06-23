-- V1__init_notification_schema.sql
-- Notification schema per design 5.7

CREATE TABLE notification (
    notification_id UUID        PRIMARY KEY,
    customer_id     UUID        NOT NULL,
    policy_id       UUID,
    type            VARCHAR(50) NOT NULL,
    channel         VARCHAR(10) NOT NULL DEFAULT 'in_app',
    message         TEXT        NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'pending',
    retry_count     INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_notification_channel CHECK (channel IN ('email','in_app')),
    CONSTRAINT chk_notification_status CHECK (status IN ('pending','sent','failed')),
    CONSTRAINT uq_notification_policy_type UNIQUE (policy_id, type)
);

CREATE INDEX idx_notification_customer ON notification (customer_id);
