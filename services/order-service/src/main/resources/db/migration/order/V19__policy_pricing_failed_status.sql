ALTER TABLE policy DROP CONSTRAINT IF EXISTS chk_policy_status;
ALTER TABLE policy
    ADD CONSTRAINT chk_policy_status
    CHECK (status IN ('active','cancelled','expired','pending_payment','pricing_pending','pricing_failed'));
