# Event-Driven Migration Plan for Removing Service-to-Service Sync Calls

## Goal

Remove internal synchronous service-to-service calls and move the platform toward an event-driven architecture. The target end state is:

- Services communicate internally through events.
- Synchronous HTTP remains only for client/API Gateway to service traffic.
- Existing internal HTTP endpoints can remain temporarily during migration, then be deprecated.

## Current Sync Call Inventory

### Sync-only dependencies

- `order-service -> pricing-service`
  - `GET /pricing/quote/{quoteId}`
  - `POST /pricing/quote`
- `order-service -> billing-service`
  - `POST /internal/credits/apply-and-quote`
  - `POST /internal/invoices/void-by-endorsement`
- `claims-service -> order-service`
  - `GET /internal/policies/{policyId}`
  - `GET /internal/policies/{policyId}/exposure-segments`
- `billing-service -> order-service`
  - `GET /internal/policies/{policyId}/owner`
  - `GET /internal/orders/{orderId}/owner`

### Sync calls with fallback or inside async flow

- `claims-service -> order-service`
  - `GET /internal/orders/by-policy/{policyId}`
  - Used as fallback for missing `quote_id`.
- `order-service -> billing-service`
  - `GET /internal/credits/refundable?policy_id=...`
  - Failure falls back to `0`.
- `notification-service -> customer-service`
  - `GET /internal/customers/{customerId}/email`
  - Runs inside async notification handling, but still performs a sync lookup.

## Migration Principles

- Prefer local projections/read models over synchronous ownership or lookup calls.
- Put enough data in events to avoid downstream fetches.
- Use idempotent consumers and replay-safe events.
- Accept temporary eventual consistency where business flow allows it.
- For money-related flows, use explicit command/result events instead of blind async fire-and-forget.

## Phase 0 - Baseline and Observability

### Objective

Freeze the current dependency map and instrument it before changing behavior.

### Work

- Confirm the full list of internal sync calls.
- Add metrics for each dependency:
  - call count
  - latency
  - error rate
- Add structured logs with caller, callee, endpoint, correlation ID.
- Define the migration event contracts needed by later phases.

### Output

- Audited dependency inventory.
- Metrics dashboard or log query reference.
- Draft event contract list.

### Behavior change

- None.

## Phase 1 - Remove Billing Owner Lookups

### Objective

Remove billing-service synchronous owner lookups into order-service.

### Current calls removed

- `billing-service -> order-service /internal/policies/{policyId}/owner`
- `billing-service -> order-service /internal/orders/{orderId}/owner`

### Work

- Ensure invoice creation flows always carry `customer_id`.
- Persist `customer_id` directly on invoice records when created.
- Use local invoice data for authorization and ownership checks.
- Backfill legacy invoices missing `customer_id`.

### Required event/data checks

- Verify `OrderApproved`, `PolicyRenewed`, and `EndorsementPendingPayment` always contain `customer_id`.
- Verify all invoice creation paths populate the same field consistently.

### Risk

- Low.

### Why first

- High value, limited scope, lowest architectural risk.

## Phase 2 - Add Notification Email Projection

### Objective

Remove notification-service synchronous email lookup into customer-service.

### Current call removed

- `notification-service -> customer-service /internal/customers/{customerId}/email`

### Work

- Add customer events such as:
  - `CustomerCreated`
  - `CustomerEmailUpdated`
- Build a local notification projection table keyed by `customer_id`.
- Resolve recipient email from the local projection instead of HTTP.
- Define behavior for missing or stale email records.

### Risk

- Low to medium.

### Notes

- This is still important even though the call runs inside an async consumer.
- Review PII handling, retention, and masking requirements before rollout.

## Phase 3 - Build Claims Policy and Coverage Projection

### Objective

Remove claims-service synchronous policy and exposure-segment reads from order-service.

### Current calls removed

- `claims-service -> order-service /internal/policies/{policyId}`
- `claims-service -> order-service /internal/policies/{policyId}/exposure-segments`
- Fallback call: `claims-service -> order-service /internal/orders/by-policy/{policyId}`

### Status

Implemented pending final operational rollout/backfill strategy.

### Event-driven replacement

- Claims service consumes policy-domain events from `platform.events`:
  - `PolicyIssued`
  - `PolicyRenewed`
  - `PolicyCancelled`
  - `EndorsementApplied`
- Claims service stores local read models:
  - `claim_policy_projection`
  - `claim_exposure_segment_projection`
- FNOL ownership/status validation uses `claim_policy_projection`.
- FNOL segment resolution and payout cap checks use `claim_exposure_segment_projection`.
- `ClaimSettled` quote linkage uses the cached claim `quote_id` and local policy projection line, not an order-service HTTP fallback.

### Verification requirements

- Unit/property tests for claims pass.
- Grep has no claims-service HTTP `RestTemplate` order client or `ORDER_BASE_URL` dependency.
- Docker live event flow publishes `PolicyIssued`, verifies both projection tables, then creates a claim through claims-service using the local projection.
- Claims-service logs show no order-service internal policy/exposure/order lookup during FNOL or settlement.

### Risk

Medium.

### Notes

- Historical active policies still need replay/backfill before production cutover.
- Internal order endpoints can remain temporarily for admin/backfill tooling, but claims runtime no longer depends on them.

## Phase 4 - Replace Invoice Void Sync Calls with Events

### Objective

Remove order-service synchronous invoice void calls into billing-service.

### Current call removed

- `order-service -> billing-service /internal/invoices/void-by-endorsement`

### Status

Implemented pending final operational rollout/backfill strategy.

### Event-driven replacement

- Order service emits command-style `EndorsementInvoiceVoidRequested` events when an endorsement invoice must be voided.
- Billing service consumes `EndorsementInvoiceVoidRequested` from `endorsement.invoice.void.requested.billing.queue`.
- Billing voids unpaid matching endorsement invoices locally and emits `InvoiceVoided` for each voided invoice.
- Order no longer calls billing-service `/internal/invoices/void-by-endorsement` in runtime endorsement flows.

### Verification requirements

- Unit tests for order-service and billing-service pass.
- Grep shows no order-service runtime call to `/internal/invoices/void-by-endorsement`.
- Docker live event flow creates an endorsement invoice, cancels/voids via order-service, verifies billing consumes `EndorsementInvoiceVoidRequested`, invoice becomes `voided`, `InvoiceVoided` is published, and queues drain.

### Risk

- Medium.

### Notes

- This introduces eventual consistency between endorsement state and invoice state.
- UI and admin flows may need explicit pending/reconciled status handling.

## Phase 5 - Replace Credit Sync Logic with Projection or Saga

### Objective

Remove order-service synchronous credit calculation and credit read calls into billing-service.

### Current calls removed

- `order-service -> billing-service /internal/credits/apply-and-quote`
- `order-service -> billing-service /internal/credits/refundable`

### Option A - Local credit projection

- Billing emits events such as:
  - `CreditCreated`
  - `CreditApplied`
  - `CreditExpired`
  - `RefundCompleted`
- Order-service keeps a customer credit projection.
- Order-service computes tentative net due locally.
- Billing confirms the final financial mutation asynchronously.

### Option B - Explicit credit application saga

- Order-service emits `CreditApplicationRequested`.
- Billing validates/reserves/applies credit.
- Billing emits one of:
  - `CreditApplicationConfirmed`
  - `CreditApplicationRejected`
- Order-service transitions endorsement or renewal flow only after confirmation.

### Recommendation

- Prefer Option B for money correctness.

### Risk

- High.

### Notes

- This phase needs strong idempotency, replay safety, and duplicate-event protection.
- Reservation semantics may be required if multiple concurrent flows can consume the same credit.

## Phase 6 - Introduce Quote Snapshot Projection

### Objective

Remove order-service synchronous quote fetches from pricing-service.

### Current call removed

- `order-service -> pricing-service GET /pricing/quote/{quoteId}`

### Work

- Emit `QuoteCreated` from pricing-service with the full order-needed snapshot:
  - `quote_id`
  - `customer_id`
  - `product_id`
  - `line`
  - `final_premium_vnd`
  - `expires_at`
  - `profile`
- Build an order-side quote projection.
- Make order creation read local quote projection data.
- Define behavior when a client submits an order before the quote projection is available.

### Risk

- Medium to high.

### Notes

- This phase is feasible, but it requires strong guarantees around quote publication and consumption order.

## Phase 7 - Move Repricing to Fully Async Workflow

### Objective

Remove order-service synchronous rerating calls into pricing-service.

### Current call removed

- `order-service -> pricing-service POST /pricing/quote`

### Work

- Replace request/response rerating with an async workflow:
  - order-service emits `QuoteRequested` or `RepriceRequested`
  - pricing-service consumes and computes premium
  - pricing-service emits `QuoteCreated` or `RepriceCompleted`
- Introduce async business states such as:
  - `PRICING_PENDING`
  - `PRICED`
  - `REVIEW_PENDING`
  - `PAYMENT_PENDING`
- Update frontend/API behavior so clients poll or subscribe for status instead of receiving immediate pricing synchronously.

### Risk

- Very high.

### Notes

- This is the biggest product and workflow change in the migration.
- It should come last unless the platform explicitly accepts async quote UX.

## Recommended Execution Order

1. Phase 1 - Remove billing owner lookups.
2. Phase 2 - Add notification email projection.
3. Phase 3 - Build claims policy and coverage projection.
4. Phase 4 - Replace invoice void sync calls with events.
5. Phase 6 - Introduce quote snapshot projection.
6. Phase 5 - Replace credit sync logic with projection or saga.
7. Phase 7 - Move repricing to fully async workflow.

## End-State Checklist

- No service needs runtime synchronous ownership lookup from another service.
- No notification delivery path depends on customer-service HTTP.
- Claims validation uses only local projections.
- Invoice creation, invoice voiding, and credit operations use events.
- Order creation reads a local quote projection.
- Repricing is asynchronous and status-driven.
- Internal `/internal/**` endpoints are either deprecated or retained only for admin/backfill tooling.

## Practical Advice

- Do not migrate all flows at once.
- Finish each phase with:
  - event contract tests
  - replay/backfill plan
  - dual-run period if needed
  - observability checks
- Treat money flows and policy-state transitions as the highest integrity domains.


