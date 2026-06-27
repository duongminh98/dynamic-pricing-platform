package dpp.claims.dto;

import dpp.claims.entity.ClaimStatus;
import dpp.claims.entity.MisrepresentationSanction;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
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
    private long incurredAmount;
    private long paidAmount;
    private ClaimStatus claimStatus;
    private MisrepresentationSanction misrepresentationSanction;
    private String description;
    private Long estimatedCost;
    private List<String> attachments;
    private OffsetDateTime createdAt;
    private String adminNote;
    private String paymentReference;
    private OffsetDateTime paidAt;
}
