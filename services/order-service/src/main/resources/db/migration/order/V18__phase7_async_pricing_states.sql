ALTER TABLE endorsement_request DROP CONSTRAINT IF EXISTS chk_endorsement_status;

ALTER TABLE endorsement_request
    ADD CONSTRAINT chk_endorsement_status
    CHECK (status IN ('PRICING_PENDING','PENDING_REVIEW','APPROVED','APPROVED_PENDING_PAYMENT','APPLIED','REJECTED','VOID','CANCELLED','PRICING_FAILED'));

ALTER TABLE endorsement_request ADD COLUMN IF NOT EXISTS pricing_request_id UUID;
ALTER TABLE endorsement_request ADD COLUMN IF NOT EXISTS pricing_failed_reason VARCHAR(500);

ALTER TABLE policy DROP CONSTRAINT IF EXISTS chk_policy_status;
ALTER TABLE policy
    ADD CONSTRAINT chk_policy_status
    CHECK (status IN ('active','cancelled','expired','pending_payment','pricing_pending'));

ALTER TABLE policy ADD COLUMN IF NOT EXISTS pricing_request_id UUID;
ALTER TABLE policy ADD COLUMN IF NOT EXISTS pricing_failed_reason VARCHAR(500);
