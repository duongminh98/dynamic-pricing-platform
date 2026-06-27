ALTER TABLE invoice ADD COLUMN endorsement_request_id UUID;
ALTER TABLE invoice ADD COLUMN due_date TIMESTAMPTZ;
