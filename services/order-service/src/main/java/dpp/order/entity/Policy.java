package dpp.order.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "policy")
@Getter
@Setter
public class Policy {

    @Id
    private UUID policyId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_id", nullable = false, length = 50)
    private String productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PolicyStatus status;

    @Column(name = "policy_effective_date", nullable = false)
    private OffsetDateTime policyEffectiveDate;

    @Column(name = "policy_expiration_date", nullable = false)
    private OffsetDateTime policyExpirationDate;

    @Column(name = "renewal_number", nullable = false)
    private int renewalNumber;

    @Column(name = "is_renewal", nullable = false)
    private boolean renewal;

    @Column(name = "years_since_first_policy", nullable = false)
    private int yearsSinceFirstPolicy;

    @Column(name = "policy_count_prior", nullable = false)
    private int policyCountPrior;

    @Column(name = "cancel_date")
    private OffsetDateTime cancelDate;

    @Column(name = "final_premium_vnd", nullable = false)
    private long finalPremiumVnd;

    @Column(name = "asset_key", length = 255)
    private String assetKey;

    @Column(name = "line", length = 20)
    private String line;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
