-- V7__add_quote_id.sql
-- Cache the originating quote_id on the claim at FNOL time so ClaimSettled can
-- emit it without a settle-time call to order-service (avoids losing quote_id
-- on transient order outages). Calibration drift joins outcomes to predictions
-- by quote_id.

ALTER TABLE claim ADD COLUMN quote_id UUID;
