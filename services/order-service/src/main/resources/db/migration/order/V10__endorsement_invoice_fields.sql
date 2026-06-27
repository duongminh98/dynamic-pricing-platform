ALTER TABLE endorsement_request ADD COLUMN invoice_id UUID;
ALTER TABLE endorsement_request ADD COLUMN due_date TIMESTAMPTZ;
