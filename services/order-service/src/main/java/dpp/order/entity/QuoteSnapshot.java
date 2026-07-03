package dpp.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "quote_snapshot")
@Getter
@Setter
public class QuoteSnapshot {
    @Id
    @Column(name = "quote_id", nullable = false)
    private UUID quoteId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_id", nullable = false, length = 50)
    private String productId;

    @Column(name = "line", length = 20)
    private String line;

    @Column(name = "trip_duration_days")
    private Integer tripDurationDays;

    @Column(name = "coverage_amount_vnd")
    private Long coverageAmountVnd;

    @Column(name = "deductible_vnd")
    private Long deductibleVnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", columnDefinition = "jsonb")
    private String profile;

    @Column(name = "final_premium_vnd", nullable = false)
    private long finalPremiumVnd;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;
}
