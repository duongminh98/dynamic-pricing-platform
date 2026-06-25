package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Result of a customer endorsement submission.
 *
 * <ul>
 *   <li>Non-material change → {@code status = "applied"} with the updated {@code policy}.</li>
 *   <li>Material change → {@code status = "pending_review"} with the created
 *       {@code endorsementRequestId} and a provisional {@code quotedPremiumVnd}
 *       so the customer knows the expected new premium before admin approval.</li>
 * </ul>
 */
@Getter
@Setter
public class EndorsementResult {

    private String status;
    private UUID endorsementRequestId;
    private PolicyResponse policy;
    private Long quotedPremiumVnd;

    public static EndorsementResult applied(PolicyResponse policy) {
        EndorsementResult r = new EndorsementResult();
        r.setStatus("applied");
        r.setPolicy(policy);
        return r;
    }

    public static EndorsementResult pendingReview(UUID endorsementRequestId, Long quotedPremiumVnd) {
        EndorsementResult r = new EndorsementResult();
        r.setStatus("pending_review");
        r.setEndorsementRequestId(endorsementRequestId);
        r.setQuotedPremiumVnd(quotedPremiumVnd);
        return r;
    }
}
