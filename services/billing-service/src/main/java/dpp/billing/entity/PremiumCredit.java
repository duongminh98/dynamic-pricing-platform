package dpp.billing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "premium_credit")
@Getter
@Setter
public class PremiumCredit {

    @Id
    private UUID creditId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "source_endorsement_id", nullable = false)
    private UUID sourceEndorsementId;

    @Column(name = "original_amount_vnd", nullable = false)
    private long originalAmountVnd;

    @Column(name = "remaining_amount_vnd", nullable = false)
    private long remainingAmountVnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreditStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
