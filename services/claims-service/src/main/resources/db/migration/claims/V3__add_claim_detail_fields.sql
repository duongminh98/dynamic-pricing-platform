-- V3__add_claim_detail_fields.sql
-- Add description, location, estimated_cost to claim table for richer FNOL data.

ALTER TABLE claim ADD COLUMN description VARCHAR(2000);
ALTER TABLE claim ADD COLUMN location VARCHAR(500);
ALTER TABLE claim ADD COLUMN estimated_cost BIGINT;
