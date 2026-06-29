package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Customer-facing endorsement request body.
 *
 * <p>Note (R23.9 security gap fix): there is intentionally NO {@code review_decision}
 * field here. A Material_Change can never be self-approved by the customer; it is
 * routed to a PENDING_REVIEW endorsement request that only an Administrator can
 * approve or reject via the {@code /admin/endorsements/**} endpoints.
 */
@Getter
@Setter
public class EndorsementRequest {
    private Map<String, Object> change;
    private OffsetDateTime effectiveDate;
}
