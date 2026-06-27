package dpp.order.dto;

import dpp.order.entity.EndorsementStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class EndorsementCancelResponse {
    private UUID endorsementRequestId;
    private UUID policyId;
    private EndorsementStatus status;
    private OffsetDateTime cancelledAt;
    private boolean invoiceVoided;
    private boolean policyChanged;
}
