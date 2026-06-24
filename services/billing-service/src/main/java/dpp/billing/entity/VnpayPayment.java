package dpp.billing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** VNPAY payment attempt for an invoice (task 21.1, R33.1/R33.2). */
@Entity
@Table(name = "vnpay_payment")
@Getter
@Setter
public class VnpayPayment {

    @Id
    private UUID paymentId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "vnp_txn_ref", nullable = false, unique = true, length = 64)
    private String vnpTxnRef;

    @Column(name = "amount_vnd", nullable = false)
    private long amountVnd;

    @Column(name = "vnp_transaction_no", length = 64)
    private String vnpTransactionNo;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "vnp_response_code", length = 10)
    private String vnpResponseCode;

    @Column(name = "vnp_bank_code", length = 20)
    private String vnpBankCode;

    @Column(name = "raw_return", columnDefinition = "jsonb")
    private String rawReturn;

    @Column(name = "raw_ipn", columnDefinition = "jsonb")
    private String rawIpn;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
