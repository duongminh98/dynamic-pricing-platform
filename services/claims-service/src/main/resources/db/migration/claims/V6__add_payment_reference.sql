-- V6__add_payment_reference.sql
-- Add payment_reference and paid_at columns for claim settlement proof.
-- Admin must approve only after transferring funds; payment_reference is the
-- bank transaction evidence, paid_at records when the transfer occurred.

ALTER TABLE claim ADD COLUMN payment_reference VARCHAR(100);
ALTER TABLE claim ADD COLUMN paid_at TIMESTAMPTZ;
