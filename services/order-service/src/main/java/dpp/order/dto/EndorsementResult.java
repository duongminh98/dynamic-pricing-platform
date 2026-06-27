package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of a customer endorsement submission.
 *
 * <p>Every endorsement is a material change routed to PENDING_REVIEW.
 * The response includes the provisional quoted premium, difference, and
 * pro-rated charge so the customer knows the expected cost before admin approval.
 */
@Getter
@Setter
public class EndorsementResult {

    private UUID endorsementRequestId;
    private UUID policyId;
    private String status;
    private OffsetDateTime effectiveDate;
    private boolean materialChange;
    private Long quotedPremiumVnd;
    private Long differenceVnd;
    private OffsetDateTime submittedAt;

    public static EndorsementResult pendingReview(UUID endorsementRequestId, Long quotedPremiumVnd,
                                                   long differenceVnd, long proRatedChargeVnd,
                                                   OffsetDateTime effectiveDate, OffsetDateTime submittedAt) {
        EndorsementResult r = new EndorsementResult();
        r.setEndorsementRequestId(endorsementRequestId);
        r.setStatus("PENDING_REVIEW");
        r.setMaterialChange(true);
        r.setQuotedPremiumVnd(quotedPremiumVnd);
        r.setDifferenceVnd(differenceVnd);
        r.setEffectiveDate(effectiveDate);
        r.setSubmittedAt(submittedAt);
        return r;
    }
}
