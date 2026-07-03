package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class RenewalPreviewResponse {
    private UUID policyId;
    private int renewalNumber;
    private OffsetDateTime newEffectiveDate;
    private OffsetDateTime newExpirationDate;
    private long currentPremiumVnd;
    private long renewedPremiumVnd;
    private long creditAppliedVnd;
    private long netDueVnd;
    private long coverageAmountVnd;
    private long deductibleVnd;
    private boolean paymentRequired;
    private String status;
    private UUID pricingRequestId;
}
