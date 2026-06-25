-- V5__endorsement_request.sql
-- R23.9 / design §4.2: pending Material_Change endorsement requests.
-- A customer-submitted material change is NOT applied immediately; it lands here
-- in PENDING_REVIEW and only an Administrator can approve (apply) or reject it.

CREATE TABLE endorsement_request (
    endorsement_request_id UUID        PRIMARY KEY,
    policy_id              UUID        NOT NULL REFERENCES policy (policy_id),
    customer_id            UUID        NOT NULL,
    change_set             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    effective_date         TIMESTAMPTZ NOT NULL,
    coverage_amount_vnd    BIGINT,
    deductible_vnd         BIGINT,
    status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
    review_reason          VARCHAR(500),
    reviewed_by            VARCHAR(36),
    reviewed_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_endorsement_status CHECK (status IN ('PENDING_REVIEW','APPROVED','REJECTED'))
);

CREATE INDEX idx_endorsement_request_status ON endorsement_request (status, created_at);
CREATE INDEX idx_endorsement_request_policy ON endorsement_request (policy_id);
