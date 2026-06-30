ALTER TABLE invoice DROP CONSTRAINT IF EXISTS chk_invoice_status;

ALTER TABLE invoice
    ADD CONSTRAINT chk_invoice_status CHECK (status IN ('unpaid', 'paid', 'voided'));
