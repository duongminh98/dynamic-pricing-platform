package dpp.billing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "credit_application")
@Getter
@Setter
public class CreditApplication {

    @Id
    private UUID applicationId;

    @Column(name = "credit_id", nullable = false)
    private UUID creditId;

    @Column(name = "applied_to_invoice_id", nullable = false)
    private UUID appliedToInvoiceId;

    @Column(name = "amount_applied_vnd", nullable = false)
    private long amountAppliedVnd;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
