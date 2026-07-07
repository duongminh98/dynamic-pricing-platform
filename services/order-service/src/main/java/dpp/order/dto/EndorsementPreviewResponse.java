package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class EndorsementPreviewResponse {
    private UUID policyId;
    private OffsetDateTime effectiveDate;
    private boolean materialChange;
    private long currentPremiumVnd;
    private Long quotedPremiumVnd;
    private long differenceVnd;
    private long proRatedChargeVnd;
    private long remainingDays;
    private long termDays;
    private long coverageAmountVnd;
    private long deductibleVnd;
    private String status;
    private UUID pricingRequestId;
    private String pricingFailedReason;
}
