-- V14__endorsement_status_cancelled.sql
-- Add CANCELLED to the endorsement_status CHECK constraint.
-- A customer can self-cancel an endorsement in PENDING_REVIEW or APPROVED_PENDING_PAYMENT.

ALTER TABLE endorsement_request DROP CONSTRAINT IF EXISTS chk_endorsement_status;

ALTER TABLE endorsement_request
    ADD CONSTRAINT chk_endorsement_status
    CHECK (status IN ('PENDING_REVIEW','APPROVED','APPROVED_PENDING_PAYMENT','APPLIED','REJECTED','VOID','CANCELLED'));
