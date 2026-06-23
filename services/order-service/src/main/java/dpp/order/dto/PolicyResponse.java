package dpp.order.dto;

import dpp.order.entity.PolicyStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class PolicyResponse {
    private UUID policyId;
    private UUID orderId;
    private UUID customerId;
    private String productId;
    private PolicyStatus status;
    private OffsetDateTime policyEffectiveDate;
    private OffsetDateTime policyExpirationDate;
    private int renewalNumber;
    private boolean renewal;
    private long finalPremiumVnd;
    private OffsetDateTime cancelDate;
    private OffsetDateTime createdAt;
}
