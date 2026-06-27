package dpp.order.entity;

/**
 * Lifecycle of a Material_Change endorsement request (R23.9 / design §4.2).
 * A customer-submitted material change starts as PENDING_REVIEW and can only
 * transition to APPROVED or REJECTED by an Administrator.
 */
public enum EndorsementStatus {
    PENDING_REVIEW, APPROVED, APPROVED_PENDING_PAYMENT, APPLIED, REJECTED, VOID
}
