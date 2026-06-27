package dpp.order.dto;

import dpp.order.entity.PolicyStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class PolicyDetailResponse {
    private UUID policyId;
    private UUID orderId;
    private UUID customerId;
    private String productId;
    private PolicyStatus status;
    private OffsetDateTime policyEffectiveDate;
    private OffsetDateTime policyExpirationDate;
    private int renewalNumber;
    private boolean renewal;
    private int yearsSinceFirstPolicy;
    private int policyCountPrior;
    private long finalPremiumVnd;
    private String assetKey;
    private OffsetDateTime cancelDate;
    private OffsetDateTime createdAt;
    private List<ExposureSegmentResponse> exposureSegments;
    private List<EndorsementRequestResponse> endorsements;
    private List<PolicyDocumentResponse> documents;
}
