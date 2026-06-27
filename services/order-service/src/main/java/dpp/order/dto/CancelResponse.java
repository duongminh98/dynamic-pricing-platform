package dpp.order.dto;

import dpp.order.entity.PolicyStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class CancelResponse {
    private UUID policyId;
    private PolicyStatus status;
    private OffsetDateTime cancelDate;
    private long remainingDays;
    private long termDays;
}
