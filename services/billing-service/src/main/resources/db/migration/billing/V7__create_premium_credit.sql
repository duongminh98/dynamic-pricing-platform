CREATE TABLE premium_credit (
    credit_id        UUID PRIMARY KEY,
    policy_id        UUID NOT NULL,
    customer_id      UUID NOT NULL,
    source_endorsement_id UUID NOT NULL,
    original_amount_vnd   BIGINT NOT NULL,
    remaining_amount_vnd  BIGINT NOT NULL,
    status           VARCHAR(20) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_credit_policy_status ON premium_credit (policy_id, status);

CREATE TABLE credit_application (
    application_id    UUID PRIMARY KEY,
    credit_id         UUID NOT NULL,
    applied_to_invoice_id UUID NOT NULL,
    amount_applied_vnd BIGINT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_credit_app_credit FOREIGN KEY (credit_id) REFERENCES premium_credit (credit_id)
);

CREATE INDEX idx_credit_app_credit ON credit_application (credit_id);
CREATE INDEX idx_credit_app_invoice ON credit_application (applied_to_invoice_id);
