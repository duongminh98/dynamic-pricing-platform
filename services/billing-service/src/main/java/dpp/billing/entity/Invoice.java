package dpp.billing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "invoice")
@Getter
@Setter
public class Invoice {

    @Id
    private UUID invoiceId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "amount_vnd", nullable = false)
    private long amountVnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private InvoiceStatus status;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "endorsement_request_id")
    private UUID endorsementRequestId;

    @Column(name = "due_date")
    private OffsetDateTime dueDate;

    @Column(name = "credit_applied_vnd", nullable = false)
    private long creditAppliedVnd = 0;

    @Column(name = "net_amount_vnd", nullable = false)
    private long netAmountVnd = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
