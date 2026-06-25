package dpp.order.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "order_")
@Getter
@Setter
public class OrderEntity {

    @Id
    private UUID orderId;

    @Column(name = "quote_id", nullable = false, unique = true)
    private UUID quoteId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_id", nullable = false, length = 50)
    private String productId;

    @Column(name = "final_premium_vnd", nullable = false)
    private long finalPremiumVnd;

    @Column(name = "line", length = 20)
    private String line;

    @Column(name = "trip_duration_days")
    private Integer tripDurationDays;

    @Column(name = "coverage_amount_vnd")
    private Long coverageAmountVnd;

    @Column(name = "deductible_vnd")
    private Long deductibleVnd;

    /**
     * Full risk profile that was quoted/priced, stored as JSON. Propagated to the
     * issued policy's first exposure segment so endorsements can re-rate against the
     * complete product feature set rather than only the changed attributes.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_profile", columnDefinition = "jsonb")
    private String riskProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_decision", length = 10)
    private ReviewDecision reviewDecision;

    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
