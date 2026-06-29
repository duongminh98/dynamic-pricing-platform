-- Renewal issues a new policy in 'pending_payment' until its invoice is paid
-- (contract section 11; InvoicePaidListener flips it to 'active'). The original
-- V1 chk_policy_status omitted that value, so renewal commits hit a constraint
-- violation. Widen the constraint to include it.
ALTER TABLE policy DROP CONSTRAINT chk_policy_status;
ALTER TABLE policy ADD CONSTRAINT chk_policy_status
    CHECK (status IN ('active','cancelled','expired','pending_payment'));
