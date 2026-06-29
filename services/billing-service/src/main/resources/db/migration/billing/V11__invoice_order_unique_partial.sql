-- V4's uq_invoice_order_id enforced one invoice per order_id GLOBALLY, which
-- blocks renewal invoices: a renewal reuses the original order_id but is a
-- distinct invoice (keyed on policy_id in the app layer). Renewal commits hit a
-- duplicate-key violation on the already-paid initial invoice.
--
-- createInvoice's idempotency is actually three-way:
--   * initial order invoice  -> dedup on order_id   (policy_id IS NULL)
--   * renewal invoice         -> dedup on policy_id   (findByPolicyId)
--   * endorsement invoice     -> dedup on endorsement_request_id
-- so the DB backstop on order_id should only cover the initial-invoice case.
-- Replace the global unique with a partial unique scoped to initial invoices,
-- and add a partial unique backstop for renewal invoices (policy_id, excluding
-- endorsement invoices which dedup on their own key).

ALTER TABLE invoice DROP CONSTRAINT uq_invoice_order_id;

CREATE UNIQUE INDEX uq_invoice_order_initial
    ON invoice (order_id)
    WHERE policy_id IS NULL;

CREATE UNIQUE INDEX uq_invoice_renewal_policy
    ON invoice (policy_id)
    WHERE policy_id IS NOT NULL AND endorsement_request_id IS NULL;
