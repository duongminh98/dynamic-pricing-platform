-- V4: persist quote line + travel trip duration on the order so policy issuance
-- can set the correct term length for travel (R22.3 / R34.1) without re-querying pricing.
ALTER TABLE order_ ADD COLUMN IF NOT EXISTS line VARCHAR(20);
ALTER TABLE order_ ADD COLUMN IF NOT EXISTS trip_duration_days INT;
