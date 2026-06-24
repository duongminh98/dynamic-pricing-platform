package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Exposure segment view for cross-service consumers (Claims) to resolve the
 * segment covering an occurrence date and its coverage/deductible (design 5.5,
 * R27.3, R28.5). Monetary amounts are VND integers (C-4).
 */
@Getter
@Setter
public class ExposureSegmentResponse {
    private UUID segmentId;
    private UUID policyId;
    private int exposureSegmentSeq;
    private OffsetDateTime segmentStart;
    private OffsetDateTime segmentEnd;
    private double earnedExposureYears;
    private long coverageAmountVnd;
    private long deductibleVnd;
}
