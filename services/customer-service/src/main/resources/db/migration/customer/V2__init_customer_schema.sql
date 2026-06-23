-- V2__init_customer_schema.sql
-- Customer DB schema per design 5.1

CREATE TABLE account (
    account_id       UUID        PRIMARY KEY,
    keycloak_subject VARCHAR(36) NOT NULL,
    email            VARCHAR(254) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    failed_login_count INT       NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    CONSTRAINT uq_account_keycloak_subject UNIQUE (keycloak_subject),
    CONSTRAINT uq_account_email UNIQUE (email),
    CONSTRAINT chk_account_email CHECK (length(email) <= 254)
);

CREATE TABLE customer_profile (
    customer_id       UUID        PRIMARY KEY,
    account_id        UUID        NOT NULL REFERENCES account (account_id),
    age               INT         NOT NULL,
    gender            VARCHAR(10) NOT NULL,
    province          VARCHAR(50) NOT NULL,
    region            VARCHAR(50) NOT NULL,
    urban_tier        VARCHAR(10) NOT NULL,
    occupation        VARCHAR(100) NOT NULL,
    income_level      VARCHAR(20) NOT NULL,
    monthly_income_vnd BIGINT     NOT NULL,
    marital_status    VARCHAR(20) NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_profile_age CHECK (age BETWEEN 18 AND 100),
    CONSTRAINT chk_profile_gender CHECK (gender IN ('male', 'female', 'other')),
    CONSTRAINT chk_profile_income CHECK (monthly_income_vnd BETWEEN 0 AND 999999999999)
);

CREATE TABLE profile_version (
    version_id      UUID        PRIMARY KEY,
    customer_id     UUID        NOT NULL REFERENCES customer_profile (customer_id),
    line            VARCHAR(20) NOT NULL,
    line_attributes JSONB       NOT NULL DEFAULT '{}',
    effective_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_profile_version_line CHECK (line IN ('health', 'motorbike', 'car', 'home', 'accident', 'travel'))
);

CREATE UNIQUE INDEX uq_customer_profile_account_id ON customer_profile (account_id);
CREATE INDEX idx_profile_version_customer_id ON profile_version (customer_id);