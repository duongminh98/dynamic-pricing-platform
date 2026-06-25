-- V6: persist quote coverage_amount_vnd + deductible_vnd on the order so policy
-- issuance can stamp the first exposure segment with the real coverage/deductible.
-- Without this the issued segment had coverage=0, making the claim payout cap 0
-- (coverage - deductible) so no claim could ever be approved (R27.3/R28.5).
ALTER TABLE order_ ADD COLUMN IF NOT EXISTS coverage_amount_vnd BIGINT;
ALTER TABLE order_ ADD COLUMN IF NOT EXISTS deductible_vnd BIGINT;
