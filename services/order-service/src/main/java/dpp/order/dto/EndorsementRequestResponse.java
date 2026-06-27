package dpp.order.dto;

import dpp.order.entity.EndorsementStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-facing view of a Material_Change endorsement request (review queue,
 * approve, and reject responses).
 */
@Getter
@Setter
public class EndorsementRequestResponse {
    private UUID endorsementRequestId;
    private UUID policyId;
    private UUID customerId;
    private EndorsementStatus status;
    private Map<String, Object> change;
    private OffsetDateTime effectiveDate;
    private boolean materialChange;
    private Long currentPremiumVnd;
    private Long quotedPremiumVnd;
    private Long differenceVnd;
    private String reviewReason;
    private String reviewedBy;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime createdAt;
    private UUID invoiceId;
    private OffsetDateTime dueDate;
    private OffsetDateTime cancelledAt;
}
