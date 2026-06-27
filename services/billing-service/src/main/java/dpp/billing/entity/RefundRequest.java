package dpp.billing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refund_request")
@Getter
@Setter
public class RefundRequest {

    @Id
    private UUID refundId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "credit_id")
    private UUID creditId;

    @Column(name = "amount_vnd", nullable = false)
    private long amountVnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "completed_by", length = 36)
    private String completedBy;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
