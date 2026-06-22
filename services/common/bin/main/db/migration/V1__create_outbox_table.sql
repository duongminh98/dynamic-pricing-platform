-- Outbox table for Transactional Outbox pattern (§2.4.3).
-- Each service owns its own outbox in its own database.
-- This migration is provided as a template; copy it into each service's
-- Flyway migration directory.

CREATE TABLE IF NOT EXISTS outbox (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        VARCHAR(64) NOT NULL UNIQUE,           -- R10.1: unique event id for dedup
    event_type      VARCHAR(100) NOT NULL,                 -- e.g. PolicyIssued, InvoicePaid
    payload         JSONB       NOT NULL,                  -- event payload
    status          VARCHAR(10) NOT NULL DEFAULT 'NEW',    -- NEW | SENT
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status ON outbox (status);
