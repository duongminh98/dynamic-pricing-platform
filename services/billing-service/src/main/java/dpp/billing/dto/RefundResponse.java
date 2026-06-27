package dpp.billing.dto;

import dpp.billing.entity.RefundStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class RefundResponse {
    private UUID refundId;
    private UUID policyId;
    private UUID customerId;
    private UUID creditId;
    private long amountVnd;
    private RefundStatus status;
    private String paymentReference;
    private String note;
    private OffsetDateTime requestedAt;
    private String completedBy;
    private OffsetDateTime completedAt;
}
