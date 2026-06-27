package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EndorsementPreviewResponse {
    private long currentPremiumVnd;
    private long quotedPremiumVnd;
    private boolean materialChange;
    private long differenceVnd;
    private long proRatedChargeVnd;
    private long remainingDays;
    private long termDays;
}
