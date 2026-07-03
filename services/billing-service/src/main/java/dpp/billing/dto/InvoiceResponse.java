package dpp.billing.dto;

import dpp.billing.entity.InvoiceStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class InvoiceResponse {
    private UUID invoiceId;
    private UUID orderId;
    private UUID policyId;
    private UUID customerId;
    private long amountVnd;
    private InvoiceStatus status;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
    private UUID endorsementRequestId;
    private OffsetDateTime dueDate;
    private long creditAppliedVnd;
    private long netAmountVnd;
}
