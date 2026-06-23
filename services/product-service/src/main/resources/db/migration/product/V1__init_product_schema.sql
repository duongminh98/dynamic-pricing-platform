-- Outbox table for Transactional Outbox pattern (section 2.4.3).
CREATE TABLE IF NOT EXISTS outbox (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        VARCHAR(64) NOT NULL UNIQUE,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB       NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'NEW',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status ON outbox (status);

-- ── Product table ────────────────────────────────────────────────────────
CREATE TABLE product (
    product_id          VARCHAR(32)     NOT NULL,
    category            VARCHAR(20)     NOT NULL,
    product_name        VARCHAR(200)    NOT NULL,
    coverage_amount_vnd BIGINT          NOT NULL,
    deductible_vnd      BIGINT          NOT NULL DEFAULT 0,
    base_premium_vnd    BIGINT          NOT NULL,
    admin_fee_vnd       BIGINT          NOT NULL DEFAULT 0,
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    PRIMARY KEY (product_id),
    CONSTRAINT chk_product_category CHECK (category IN ('health','motorbike','car','home','accident','travel'))
);

-- ── Coverage option table ────────────────────────────────────────────────
CREATE TABLE coverage_option (
    coverage_option_id  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          VARCHAR(32)      NOT NULL REFERENCES product(product_id),
    coverage_amount_vnd BIGINT           NOT NULL,
    deductible_vnd      BIGINT           NOT NULL DEFAULT 0,
    base_premium_vnd    BIGINT           NOT NULL,
    admin_fee_vnd       BIGINT           NOT NULL DEFAULT 0
);

CREATE INDEX idx_coverage_option_product ON coverage_option (product_id);

-- ── Rate version table (append-only, design section 5.2) ─────────────────
CREATE TABLE rate_version (
    rate_version_id  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    effective_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_by       VARCHAR(100)    NOT NULL,
    is_current       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_rate_version_current ON rate_version (is_current) WHERE is_current = TRUE;

-- ── Loading factor table ─────────────────────────────────────────────────
CREATE TABLE loading_factor (
    loading_factor_id UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    rate_version_id   UUID            NOT NULL REFERENCES rate_version(rate_version_id),
    line              VARCHAR(20)      NOT NULL,
    loading_value     DOUBLE PRECISION NOT NULL,
    CONSTRAINT chk_loading_line CHECK (line IN ('health','motorbike','car','home','accident','travel'))
);

CREATE INDEX idx_loading_factor_version ON loading_factor (rate_version_id);

-- ── Eligibility rule table ───────────────────────────────────────────────
CREATE TABLE eligibility_rule (
    rule_id           UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    rate_version_id   UUID            NOT NULL REFERENCES rate_version(rate_version_id),
    line              VARCHAR(20)      NOT NULL,
    rule_type         VARCHAR(20)      NOT NULL,
    params            JSONB           NOT NULL DEFAULT '{}',
    action            VARCHAR(10)      NOT NULL,
    CONSTRAINT chk_rule_line CHECK (line IN ('health','motorbike','car','home','accident','travel')),
    CONSTRAINT chk_rule_type CHECK (rule_type IN ('age_limit','coverage_cap','health_combo','vehicle_limit')),
    CONSTRAINT chk_rule_action CHECK (action IN ('ACCEPT','REFER','DECLINE'))
);

CREATE INDEX idx_eligibility_rule_version ON eligibility_rule (rate_version_id);
