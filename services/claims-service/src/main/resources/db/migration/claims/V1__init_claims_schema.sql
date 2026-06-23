-- V1__init_claims_schema.sql
-- Claims schema per design 5.5

CREATE TABLE claim (
    claim_id                UUID        PRIMARY KEY,
    policy_id               UUID        NOT NULL,
    exposure_segment_seq    INT         NOT NULL,
    customer_id             UUID        NOT NULL,
    occurrence_date         TIMESTAMPTZ NOT NULL,
    report_date             TIMESTAMPTZ NOT NULL,
    loss_type               VARCHAR(50) NOT NULL,
    severity_level          VARCHAR(10) NOT NULL,
    incurred_amount         BIGINT      NOT NULL DEFAULT 0,
    paid_amount             BIGINT      NOT NULL DEFAULT 0,
    claim_status            VARCHAR(10) NOT NULL DEFAULT 'pending',
    misrepresentation_sanction VARCHAR(20),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_claim_status CHECK (claim_status IN ('pending','approved','rejected')),
    CONSTRAINT chk_claim_severity CHECK (severity_level IN ('low','medium','high')),
    CONSTRAINT chk_claim_report_date CHECK (report_date >= occurrence_date),
    CONSTRAINT chk_claim_sanction CHECK (misrepresentation_sanction IS NULL OR misrepresentation_sanction IN ('reject','proportional','cancel'))
);

CREATE INDEX idx_claim_policy ON claim (policy_id);
CREATE INDEX idx_claim_customer ON claim (customer_id);
