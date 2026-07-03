ALTER TABLE invoice ADD COLUMN IF NOT EXISTS customer_id UUID;

CREATE INDEX IF NOT EXISTS idx_invoice_customer_id ON invoice(customer_id);
