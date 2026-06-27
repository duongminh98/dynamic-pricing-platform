-- V11__endorsement_status_expand.sql
-- Expand the endorsement_status CHECK constraint to include the new lifecycle states:
--   APPROVED_PENDING_PAYMENT — admin approved, waiting for invoice payment (premium increase)
--   APPLIED                  — endorsement fully applied to the policy (terminal)
--   VOID                     — endorsement voided (e.g. invoice unpaid past deadline)

ALTER TABLE endorsement_request DROP CONSTRAINT IF EXISTS chk_endorsement_status;

ALTER TABLE endorsement_request
    ADD CONSTRAINT chk_endorsement_status
    CHECK (status IN ('PENDING_REVIEW','APPROVED','APPROVED_PENDING_PAYMENT','APPLIED','REJECTED','VOID'));
