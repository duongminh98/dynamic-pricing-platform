package dpp.claims.dto;

import dpp.claims.entity.ClaimStatus;
import dpp.claims.entity.MisrepresentationSanction;
import dpp.claims.entity.SeverityLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class ClaimResponse {
    private UUID claimId;
    private UUID policyId;
    private int exposureSegmentSeq;
    private UUID customerId;
    private OffsetDateTime occurrenceDate;
    private OffsetDateTime reportDate;
    private String lossType;
    private SeverityLevel severityLevel;
    private long incurredAmount;
    private long paidAmount;
    private ClaimStatus claimStatus;
    private MisrepresentationSanction misrepresentationSanction;
    private OffsetDateTime createdAt;
}
