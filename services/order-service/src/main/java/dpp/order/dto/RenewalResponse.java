package dpp.order.dto;

import dpp.order.entity.PolicyStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class RenewalResponse {
    private UUID policyId;
    private UUID previousPolicyId;
    private PolicyStatus status;
    private int renewalNumber;
    private long renewedPremiumVnd;
    private long creditAppliedVnd;
    private long netDueVnd;
    private UUID invoiceId;
    private boolean paymentRequired;
    private OffsetDateTime newEffectiveDate;
    private OffsetDateTime newExpirationDate;
    private UUID pricingRequestId;
    private String pricingFailedReason;
}
