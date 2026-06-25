-- V4: enforce one invoice per order (task 20.11, R33.4). createInvoice is now
-- idempotent on order_id, and this UNIQUE constraint is the DB backstop that
-- prevents duplicate invoices if two commit-then-REST replays race past the
-- application-level findByOrderId check. The unique index also serves the
-- order_id lookup, so the prior non-unique index is dropped to avoid redundancy.
DROP INDEX IF EXISTS idx_invoice_order;

-- Defensive: remove any pre-existing duplicate invoices per order_id (created by
-- the old non-idempotent createInvoice before task 20.11) so the UNIQUE index can
-- be built on dirty volumes. Keep the earliest invoice per order; break ties on
-- the smaller invoice_id.
DELETE FROM invoice a
    USING invoice b
    WHERE a.order_id = b.order_id
      AND (a.created_at > b.created_at
           OR (a.created_at = b.created_at AND a.invoice_id > b.invoice_id));

ALTER TABLE invoice ADD CONSTRAINT uq_invoice_order_id UNIQUE (order_id);
