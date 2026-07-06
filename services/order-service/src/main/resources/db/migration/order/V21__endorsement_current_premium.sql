-- Persist the policy premium captured at pricing time so the admin view can show
-- current premium + difference even after the endorsement is APPLIED (at which
-- point the policy's live premium has already moved to the quoted value).
ALTER TABLE endorsement_request
    ADD COLUMN current_premium_vnd BIGINT;
