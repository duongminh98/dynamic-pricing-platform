-- Outbox table for Transactional Outbox pattern (2.4.3).
-- Each service owns its own outbox in its own database.

CREATE TABLE IF NOT EXISTS outbox (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        VARCHAR(64) NOT NULL UNIQUE,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB       NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'NEW',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox (status);
