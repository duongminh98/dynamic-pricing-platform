package dpp.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Map;

@Getter
@Setter
public class EndorsementRequest {
    @NotNull
    private Map<String, Object> change;
    @NotNull
    private OffsetDateTime effectiveDate;

    /**
     * Administrator re-review decision for a Material_Change endorsement (R23.9):
     * one of APPROVE or REJECT. Required when the change is material; ignored for
     * non-material changes. A material change without APPROVE is rejected
     * (pending manual review), and REJECT yields ORDER_NOT_APPROVED.
     */
    private String reviewDecision;

    /**
     * New coverage amount (VND) for the endorsed segment. Optional; when absent
     * the prior segment coverage is retained.
     */
    private Long coverageAmountVnd;

    /** New deductible (VND) for the endorsed segment. Optional. */
    private Long deductibleVnd;
}
