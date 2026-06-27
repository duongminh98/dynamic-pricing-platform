-- V4__drop_severity_location_add_attachments.sql
-- Remove severity_level (admin will assess during review) and location.
-- Add attachments column for evidence URLs (photos, invoices, etc.).

ALTER TABLE claim DROP CONSTRAINT IF EXISTS chk_claim_severity;
ALTER TABLE claim DROP COLUMN IF EXISTS severity_level;
ALTER TABLE claim DROP COLUMN IF EXISTS location;
ALTER TABLE claim ADD COLUMN attachments JSONB;
