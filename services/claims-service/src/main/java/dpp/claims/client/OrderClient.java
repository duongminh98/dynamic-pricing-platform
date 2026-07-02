package dpp.claims.client;

import dpp.claims.entity.ClaimExposureSegmentProjection;
import dpp.claims.entity.ClaimPolicyProjection;
import dpp.claims.repository.ClaimExposureSegmentProjectionRepository;
import dpp.claims.repository.ClaimPolicyProjectionRepository;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Local policy projection reader retained for test compatibility during phase 3.
 * It reads claims-service projections only and performs no HTTP calls.
 */
public class OrderClient {

    private final ClaimPolicyProjectionRepository policyProjectionRepository;
    private final ClaimExposureSegmentProjectionRepository segmentProjectionRepository;

    public OrderClient(ClaimPolicyProjectionRepository policyProjectionRepository,
                       ClaimExposureSegmentProjectionRepository segmentProjectionRepository) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.segmentProjectionRepository = segmentProjectionRepository;
    }

    public Map<String, Object> getPolicy(UUID policyId) {
        ClaimPolicyProjection policy = policyProjectionRepository.findById(policyId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer_id", policy.getCustomerId().toString());
        result.put("status", policy.getStatus());
        result.put("quote_id", policy.getQuoteId() != null ? policy.getQuoteId().toString() : null);
        result.put("line", policy.getLine());
        return result;
    }

    public List<Map<String, Object>> getExposureSegments(UUID policyId) {
        return segmentProjectionRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId).stream()
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> getQuoteIdByPolicy(UUID policyId) {
        ClaimPolicyProjection policy = policyProjectionRepository.findById(policyId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quote_id", policy.getQuoteId());
        result.put("line", policy.getLine());
        return result;
    }

    private Map<String, Object> toMap(ClaimExposureSegmentProjection segment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exposure_segment_seq", segment.getExposureSegmentSeq());
        result.put("segment_start", segment.getSegmentStart().toString());
        result.put("segment_end", segment.getSegmentEnd().toString());
        result.put("coverage_amount_vnd", segment.getCoverageAmountVnd());
        result.put("deductible_vnd", segment.getDeductibleVnd());
        return result;
    }
}
