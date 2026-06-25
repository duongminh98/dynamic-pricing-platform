-- V7: persist the full risk profile that was quoted/priced on the order so policy
-- issuance can stamp the first exposure segment with the complete feature set.
-- Without this an endorsement re-rate could only send the changed attributes,
-- which the pricing engine rejects (MISSING_FEATURES) or mis-prices via defaults
-- (R23.2/R23.8).
ALTER TABLE order_ ADD COLUMN IF NOT EXISTS risk_profile JSONB;
