package dpp.claims.service;

import dpp.claims.client.OrderClient;
import dpp.claims.dto.*;
import dpp.claims.entity.*;
import dpp.claims.repository.ClaimRepository;
import dpp.common.security.CustomerId;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ClaimsService {

    private final ClaimRepository claimRepository;
    private final OrderClient orderClient;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public ClaimsService(ClaimRepository claimRepository, OrderClient orderClient, OutboxPublisher outboxPublisher) {
        this.claimRepository = claimRepository;
        this.orderClient = orderClient;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public ClaimResponse fnol(String keycloakSubject, FnolRequest request) {
        UUID policyId = request.getPolicyId();
        Map<String, Object> policy = orderClient.getPolicy(policyId);
        UUID policyOwner = UUID.fromString(String.valueOf(policy.get("customer_id")));
        UUID customerId = CustomerId.fromSubject(keycloakSubject);
        if (!policyOwner.equals(customerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }

        OffsetDateTime occurrence = request.getOccurrenceDate();
        OffsetDateTime reportDate = OffsetDateTime.now();
        if (reportDate.isBefore(occurrence)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Report date before occurrence", null);
        }

        List<Map<String, Object>> segments = orderClient.getExposureSegments(policyId);
        int segmentSeq = findSegmentSeq(segments, occurrence);

        Claim claim = new Claim();
        claim.setClaimId(UUID.randomUUID());
        claim.setPolicyId(policyId);
        claim.setExposureSegmentSeq(segmentSeq);
        claim.setCustomerId(customerId);
        claim.setOccurrenceDate(occurrence);
        claim.setReportDate(reportDate);
        claim.setLossType(request.getLossType());
        claim.setSeverityLevel(SeverityLevel.valueOf(request.getSeverityLevel()));
        claim.setIncurredAmount(0);
        claim.setPaidAmount(0);
        claim.setClaimStatus(ClaimStatus.pending);
        claim.setCreatedAt(OffsetDateTime.now());
        claim = claimRepository.save(claim);
        return toResponse(claim);
    }

    /**
     * Resolve the exposure segment covering the occurrence date and return its
     * sequence (R27.3). The occurrence must fall within some segment window
     * [segment_start, segment_end]; otherwise it is outside coverage.
     */
    private int findSegmentSeq(List<Map<String, Object>> segments, OffsetDateTime occurrence) {
        for (Map<String, Object> seg : segments) {
            OffsetDateTime start = OffsetDateTime.parse(String.valueOf(seg.get("segment_start")));
            OffsetDateTime end = OffsetDateTime.parse(String.valueOf(seg.get("segment_end")));
            if (!occurrence.isBefore(start) && !occurrence.isAfter(end)) {
                return ((Number) seg.get("exposure_segment_seq")).intValue();
            }
        }
        throw new ServiceException(ErrorCode.OCCURRENCE_OUT_OF_COVERAGE);
    }

    /**
     * Coverage minus deductible cap for the segment covering the claim occurrence
     * (R28.5). Returns the max payable amount the approver must not exceed.
     */
    private long payoutCapForClaim(Claim claim) {
        List<Map<String, Object>> segments = orderClient.getExposureSegments(claim.getPolicyId());
        for (Map<String, Object> seg : segments) {
            if (((Number) seg.get("exposure_segment_seq")).intValue() == claim.getExposureSegmentSeq()) {
                long coverage = ((Number) seg.get("coverage_amount_vnd")).longValue();
                long deductible = ((Number) seg.get("deductible_vnd")).longValue();
                return Math.max(0L, coverage - deductible);
            }
        }
        throw new ServiceException(ErrorCode.OCCURRENCE_OUT_OF_COVERAGE);
    }

    @Transactional
    public ClaimResponse approve(UUID claimId, ApproveClaimRequest request) {
        Claim claim = findClaim(claimId);
        if (claim.getClaimStatus() != ClaimStatus.pending) {
            throw new ServiceException(ErrorCode.INVALID_CLAIM_TRANSITION);
        }
        long incurred = request.getIncurredAmount();
        long paid = request.getPaidAmount();
        if (incurred <= 0 || paid < 0 || paid > incurred) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Invalid payout amounts", null);
        }
        long cap = payoutCapForClaim(claim);
        if (paid > cap) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    "Paid amount exceeds coverage minus deductible", Map.of("paid", paid, "cap", cap));
        }
        claim.setIncurredAmount(incurred);
        claim.setPaidAmount(paid);
        claim.setClaimStatus(ClaimStatus.approved);
        claim = claimRepository.save(claim);
        enqueueClaimChanged(claim);
        return toResponse(claim);
    }

    @Transactional
    public ClaimResponse reject(UUID claimId) {
        Claim claim = findClaim(claimId);
        if (claim.getClaimStatus() != ClaimStatus.pending) {
            throw new ServiceException(ErrorCode.INVALID_CLAIM_TRANSITION);
        }
        claim.setPaidAmount(0);
        claim.setClaimStatus(ClaimStatus.rejected);
        claim = claimRepository.save(claim);
        enqueueClaimChanged(claim);
        return toResponse(claim);
    }

    @Transactional
    public ClaimResponse misrepresentation(UUID claimId, MisrepresentationRequest request) {
        Claim claim = findClaim(claimId);
        MisrepresentationSanction sanction = parseSanction(request.getSanction());
        claim.setMisrepresentationSanction(sanction);

        switch (sanction) {
            case reject -> claim.setPaidAmount(0);
            case proportional -> {
                Long paidPremium = request.getPaidPremium();
                Long shouldPremium = request.getShouldPremium();
                if (paidPremium == null || shouldPremium == null || shouldPremium <= 0 || paidPremium < 0) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST,
                            "proportional sanction requires paid_premium and positive should_premium", null);
                }
                double ratio = Math.min(1.0, (double) paidPremium / shouldPremium);
                long adjusted = Math.round(claim.getPaidAmount() * ratio);
                claim.setPaidAmount(adjusted);
            }
            case cancel -> {
                claim.setPaidAmount(0);
                claim.setClaimStatus(ClaimStatus.rejected);
            }
        }
        claim = claimRepository.save(claim);
        enqueueClaimChanged(claim);
        return toResponse(claim);
    }

    private MisrepresentationSanction parseSanction(String value) {
        try {
            return MisrepresentationSanction.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ServiceException(ErrorCode.BAD_REQUEST,
                    "Invalid misrepresentation sanction", Map.of("sanction", String.valueOf(value)));
        }
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> myClaims(String keycloakSubject) {
        UUID customerId = CustomerId.fromSubject(keycloakSubject);
        return claimRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ClaimResponse getClaim(String keycloakSubject, UUID claimId, boolean isAdmin) {
        Claim claim = findClaim(claimId);
        if (!isAdmin) {
            UUID customerId = CustomerId.fromSubject(keycloakSubject);
            if (!claim.getCustomerId().equals(customerId)) {
                throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
            }
        }
        return toResponse(claim);
    }

    private Claim findClaim(UUID claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Claim not found", null));
    }

    private void enqueueClaimChanged(Claim claim) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claim_id", claim.getClaimId().toString());
        payload.put("policy_id", claim.getPolicyId().toString());
        payload.put("customer_id", claim.getCustomerId().toString());
        payload.put("status", claim.getClaimStatus().name());
        try {
            outboxPublisher.enqueue("ClaimStatusChanged", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue ClaimStatusChanged", e);
        }
    }

    private ClaimResponse toResponse(Claim claim) {
        ClaimResponse resp = new ClaimResponse();
        resp.setClaimId(claim.getClaimId());
        resp.setPolicyId(claim.getPolicyId());
        resp.setExposureSegmentSeq(claim.getExposureSegmentSeq());
        resp.setCustomerId(claim.getCustomerId());
        resp.setOccurrenceDate(claim.getOccurrenceDate());
        resp.setReportDate(claim.getReportDate());
        resp.setLossType(claim.getLossType());
        resp.setSeverityLevel(claim.getSeverityLevel());
        resp.setIncurredAmount(claim.getIncurredAmount());
        resp.setPaidAmount(claim.getPaidAmount());
        resp.setClaimStatus(claim.getClaimStatus());
        resp.setMisrepresentationSanction(claim.getMisrepresentationSanction());
        resp.setCreatedAt(claim.getCreatedAt());
        return resp;
    }
}
