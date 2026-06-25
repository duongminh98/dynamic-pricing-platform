-- V4: enforce one invoice per order (task 20.11, R33.4). createInvoice is now
-- idempotent on order_id, and this UNIQUE constraint is the DB backstop that
-- prevents duplicate invoices if two commit-then-REST replays race past the
-- application-level findByOrderId check. The unique index also serves the
-- order_id lookup, so the prior non-unique index is dropped to avoid redundancy.
DROP INDEX IF EXISTS idx_invoice_order;

ALTER TABLE invoice ADD CONSTRAINT uq_invoice_order_id UNIQUE (order_id);
