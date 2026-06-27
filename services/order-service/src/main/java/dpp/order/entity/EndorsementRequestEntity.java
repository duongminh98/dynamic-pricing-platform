package dpp.order.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A pending Material_Change endorsement request (R23.9 / design §4.2).
 *
 * <p>When a Customer submits a material change to a policy, the change is NOT
 * applied immediately. Instead it is persisted here in {@code PENDING_REVIEW}
 * state and only an Administrator can {@code APPROVE} (apply it) or
 * {@code REJECT} it. This closes the self-approval security gap where a Customer
 * could set the review decision in the request body.
 */
@Entity
@Table(name = "endorsement_request")
@Getter
@Setter
public class EndorsementRequestEntity {

    @Id
    @Column(name = "endorsement_request_id")
    private UUID endorsementRequestId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** Requested risk-attribute change set (the material attributes), stored as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_set", columnDefinition = "jsonb", nullable = false)
    private String changeSet;

    @Column(name = "effective_date", nullable = false)
    private OffsetDateTime effectiveDate;

    /** Optional new coverage amount (VND); null retains the prior segment value. */
    @Column(name = "coverage_amount_vnd")
    private Long coverageAmountVnd;

    /** Optional new deductible (VND); null retains the prior segment value. */
    @Column(name = "deductible_vnd")
    private Long deductibleVnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EndorsementStatus status;

    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Provisional premium (VND) quoted at submission time for the customer's information; applied only on admin approval. */
    @Column(name = "quoted_premium_vnd")
    private Long quotedPremiumVnd;

    /** Invoice ID for the additional charge when status = APPROVED_PENDING_PAYMENT. */
    @Column(name = "invoice_id")
    private UUID invoiceId;

    /** Due date for payment when status = APPROVED_PENDING_PAYMENT. */
    @Column(name = "due_date")
    private OffsetDateTime dueDate;
}
