-- V1__init_order_schema.sql
-- Order + policy lifecycle schema per design 5.3

CREATE TABLE order_ (
    order_id          UUID        PRIMARY KEY,
    quote_id          UUID        NOT NULL UNIQUE,
    customer_id       UUID        NOT NULL,
    product_id        VARCHAR(50) NOT NULL,
    final_premium_vnd BIGINT      NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
    review_decision   VARCHAR(10),
    review_reason     VARCHAR(500),
    reviewed_by       VARCHAR(36),
    reviewed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_order_status CHECK (status IN ('PENDING_REVIEW','PENDING_PAYMENT','REJECTED','COMPLETED','CANCELLED')),
    CONSTRAINT chk_order_review_decision CHECK (review_decision IS NULL OR review_decision IN ('APPROVE','REJECT'))
);

CREATE INDEX idx_order_customer ON order_ (customer_id);

CREATE TABLE policy (
    policy_id              UUID        PRIMARY KEY,
    order_id               UUID        NOT NULL REFERENCES order_ (order_id),
    customer_id            UUID        NOT NULL,
    product_id             VARCHAR(50) NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'active',
    policy_effective_date  TIMESTAMPTZ NOT NULL,
    policy_expiration_date TIMESTAMPTZ NOT NULL,
    renewal_number         INT         NOT NULL DEFAULT 0,
    is_renewal             BOOLEAN     NOT NULL DEFAULT FALSE,
    years_since_first_policy INT       NOT NULL DEFAULT 0,
    policy_count_prior     INT         NOT NULL DEFAULT 0,
    cancel_date            TIMESTAMPTZ,
    final_premium_vnd      BIGINT      NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_policy_status CHECK (status IN ('active','cancelled','expired')),
    CONSTRAINT chk_policy_dates CHECK (policy_expiration_date > policy_effective_date)
);

CREATE INDEX idx_policy_customer ON policy (customer_id);

CREATE TABLE exposure_segment (
    segment_id            UUID        PRIMARY KEY,
    policy_id             UUID        NOT NULL REFERENCES policy (policy_id),
    exposure_segment_seq  INT         NOT NULL,
    segment_start         TIMESTAMPTZ NOT NULL,
    segment_end           TIMESTAMPTZ NOT NULL,
    earned_exposure_years DOUBLE PRECISION NOT NULL DEFAULT 0,
    coverage_amount_vnd   BIGINT      NOT NULL,
    deductible_vnd        BIGINT      NOT NULL DEFAULT 0,
    risk_snapshot         JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_exposure_segment_policy ON exposure_segment (policy_id, exposure_segment_seq);

CREATE TABLE policy_document (
    document_id  UUID        PRIMARY KEY,
    policy_id    UUID        NOT NULL REFERENCES policy (policy_id),
    version      INT         NOT NULL,
    content      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_document_policy ON policy_document (policy_id, version);
