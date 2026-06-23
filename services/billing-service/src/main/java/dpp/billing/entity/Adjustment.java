package dpp.billing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "adjustment")
@Getter
@Setter
public class Adjustment {

    @Id
    private UUID adjustmentId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private AdjustmentType type;

    @Column(name = "amount_vnd", nullable = false)
    private long amountVnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private AdjustmentReason reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
