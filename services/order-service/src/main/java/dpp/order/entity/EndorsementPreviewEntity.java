package dpp.order.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "endorsement_preview")
@Getter
@Setter
public class EndorsementPreviewEntity {

    @Id
    @Column(name = "pricing_request_id")
    private UUID pricingRequestId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_set", columnDefinition = "jsonb", nullable = false)
    private String changeSet;

    @Column(name = "effective_date", nullable = false)
    private OffsetDateTime effectiveDate;

    @Column(name = "current_premium_vnd", nullable = false)
    private long currentPremiumVnd;

    @Column(name = "quoted_premium_vnd")
    private Long quotedPremiumVnd;

    @Column(name = "coverage_amount_vnd", nullable = false)
    private long coverageAmountVnd;

    @Column(name = "deductible_vnd", nullable = false)
    private long deductibleVnd;

    @Column(name = "remaining_days", nullable = false)
    private long remainingDays;

    @Column(name = "term_days", nullable = false)
    private long termDays;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "pricing_failed_reason", length = 500)
    private String pricingFailedReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
