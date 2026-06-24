package dpp.billing.dto;

import dpp.billing.entity.AdjustmentReason;
import dpp.billing.entity.AdjustmentType;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class AdjustmentResponse {
    private UUID adjustmentId;
    private UUID policyId;
    private AdjustmentType type;
    private long amountVnd;
    private AdjustmentReason reason;
    private OffsetDateTime createdAt;
}
