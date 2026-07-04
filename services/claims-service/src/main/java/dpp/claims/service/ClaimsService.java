package dpp.claims.service;

import dpp.claims.client.OrderClient;
import dpp.claims.dto.*;
import dpp.claims.entity.*;
import dpp.claims.repository.ClaimExposureSegmentProjectionRepository;
import dpp.claims.repository.ClaimPolicyProjectionRepository;
import dpp.claims.repository.ClaimRepository;
import dpp.common.dto.PageResponse;
import dpp.common.security.CustomerId;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(ClaimsService.class);

    private final ClaimRepository claimRepository;
    private final ClaimPolicyProjectionRepository policyProjectionRepository;
    private final ClaimExposureSegmentProjectionRepository segmentProjectionRepository;
    private final OrderClient compatibilityProjectionReader;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    @Autowired
    public ClaimsService(ClaimRepository claimRepository,
                         ClaimPolicyProjectionRepository policyProjectionRepository,
                         ClaimExposureSegmentProjectionRepository segmentProjectionRepository,
                         OutboxPublisher outboxPublisher) {
        this.claimRepository = claimRepository;
        this.policyProjectionRepository = policyProjectionRepository;
        this.segmentProjectionRepository = segmentProjectionRepository;
        this.compatibilityProjectionReader = null;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    public ClaimsService(ClaimRepository claimRepository, OrderClient compatibilityProjectionReader, OutboxPublisher outboxPublisher) {
        this.claimRepository = claimRepository;
        this.policyProjectionRepository = null;
        this.segmentProjectionRepository = null;
        this.compatibilityProjectionReader = compatibilityProjectionReader;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }
    @Transactional
    public ClaimResponse fnol(String keycloakSubject, FnolRequest request) {
        UUID policyId = request.getPolicyId();
        ClaimPolicyProjection policy = findPolicyProjection(policyId);
        UUID policyOwner = policy.getCustomerId();
        UUID customerId = CustomerId.fromSubject(keycloakSubject);
        if (!policyOwner.equals(customerId)) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null);
        }

        String policyStatus = policy.getStatus();
        if (!"active".equalsIgnoreCase(policyStatus)) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }

        OffsetDateTime occurrence = request.getOccurrenceDate();
        OffsetDateTime reportDate = OffsetDateTime.now();
        if (reportDate.isBefore(occurrence)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Report date before occurrence", null);
        }

        List<ClaimExposureSegmentProjection> segments = findSegmentProjections(policyId);
        int segmentSeq = findSegmentSeq(segments, occurrence);

        UUID quoteId = policy.getQuoteId();

        Claim claim = new Claim();
        claim.setClaimId(UUID.randomUUID());
        claim.setPolicyId(policyId);
        claim.setExposureSegmentSeq(segmentSeq);
        claim.setCustomerId(customerId);
        claim.setOccurrenceDate(occurrence);
        claim.setReportDate(reportDate);
        claim.setLossType(request.getLossType());
        claim.setIncurredAmount(0);
        claim.setPaidAmount(0);
        claim.setClaimStatus(ClaimStatus.pending);
        claim.setDescription(request.getDescription());
        claim.setEstimatedCost(request.getEstimatedCost());
        claim.setAttachments(request.getAttachments());
        claim.setCreatedAt(OffsetDateTime.now());
        claim.setQuoteId(quoteId);
        claim = claimRepository.save(claim);

        enqueueClaimSubmitted(claim);
        return toResponse(claim);
    }

    private int findSegmentSeq(List<ClaimExposureSegmentProjection> segments, OffsetDateTime occurrence) {
        int last = segments.size() - 1;
        // Scan newest-first so that if two segments ever overlap the occurrence (e.g. a legacy
        // out-of-order endorsement produced overlapping windows), the most recent coverage wins
        // rather than the stale earlier segment. New endorsements are guarded upstream in
        // order-service, but claims must resolve deterministically over whatever it is fed.
        for (int i = last; i >= 0; i--) {
            ClaimExposureSegmentProjection seg = segments.get(i);
            OffsetDateTime start = seg.getSegmentStart();
            OffsetDateTime end = seg.getSegmentEnd();
            if (i == last) {
                if (!occurrence.isBefore(start) && !occurrence.isAfter(end)) {
                    return seg.getExposureSegmentSeq();
                }
            } else {
                if (!occurrence.isBefore(start) && occurrence.isBefore(end)) {
                    return seg.getExposureSegmentSeq();
                }
            }
        }
        throw new ServiceException(ErrorCode.OCCURRENCE_OUT_OF_COVERAGE);
    }

    private long payoutCapForClaim(Claim claim) {
        ClaimExposureSegmentProjection segment = findSegmentProjection(claim.getPolicyId(), claim.getExposureSegmentSeq());
        return Math.max(0L, segment.getCoverageAmountVnd() - segment.getDeductibleVnd());
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
            throw new ServiceException(ErrorCode.PAID_AMOUNT_EXCEEDS_REMAINING_COVERAGE,
                    "Paid amount exceeds coverage minus deductible",
                    Map.of("paid_amount_vnd", paid, "segment_cap_vnd", cap));
        }
        long alreadyPaid = claimRepository.sumApprovedPaidOnSegment(
                claim.getPolicyId(), claim.getExposureSegmentSeq(), ClaimStatus.approved, claimId);
        long remainingCap = Math.max(0L, cap - alreadyPaid);
        if (paid > remainingCap) {
            throw new ServiceException(ErrorCode.PAID_AMOUNT_EXCEEDS_REMAINING_COVERAGE,
                    "Paid amount exceeds remaining aggregate coverage",
                    Map.of("paid_amount_vnd", paid, "remaining_coverage_vnd", remainingCap,
                           "segment_cap_vnd", cap, "already_paid_vnd", alreadyPaid));
        }
        claim.setIncurredAmount(incurred);
        claim.setPaidAmount(paid);
        claim.setClaimStatus(ClaimStatus.approved);
        claim.setPaymentReference(request.getPaymentReference());
        if (request.getPaidAt() != null) {
            claim.setPaidAt(request.getPaidAt());
        } else {
            claim.setPaidAt(OffsetDateTime.now());
        }
        if (request.getAdminNote() != null) {
            claim.setAdminNote(request.getAdminNote());
        }
        claim = claimRepository.save(claim);
        enqueueClaimChanged(claim);
        enqueueClaimSettled(claim);
        return toResponse(claim);
    }

    @Transactional
    public ClaimResponse reject(UUID claimId, RejectClaimRequest request) {
        Claim claim = findClaim(claimId);
        if (claim.getClaimStatus() != ClaimStatus.pending) {
            throw new ServiceException(ErrorCode.INVALID_CLAIM_TRANSITION);
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Reject reason is required", null);
        }
        claim.setPaidAmount(0);
        claim.setClaimStatus(ClaimStatus.rejected);
        claim.setAdminNote(request.getReason());
        claim = claimRepository.save(claim);
        enqueueClaimChanged(claim);
        return toResponse(claim);
    }

    @Transactional
    public ClaimResponse misrepresentation(UUID claimId, MisrepresentationRequest request) {
        Claim claim = findClaim(claimId);
        if (claim.getClaimStatus() != ClaimStatus.pending && claim.getClaimStatus() != ClaimStatus.approved) {
            throw new ServiceException(ErrorCode.INVALID_CLAIM_TRANSITION);
        }
        MisrepresentationSanction sanction = parseSanction(request.getSanction());
        claim.setMisrepresentationSanction(sanction);
        String reasonsJoined = String.join("; ", request.getReasons());
        claim.setAdminNote(reasonsJoined);

        switch (sanction) {
            case reject -> {
                claim.setPaidAmount(0);
                claim.setClaimStatus(ClaimStatus.rejected);
            }
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
                if (claim.getClaimStatus() == ClaimStatus.approved) {
                    long cap = payoutCapForClaim(claim);
                    long alreadyPaid = claimRepository.sumApprovedPaidOnSegment(
                            claim.getPolicyId(), claim.getExposureSegmentSeq(), ClaimStatus.approved, claimId);
                    long remainingCap = Math.max(0L, cap - alreadyPaid);
                    if (adjusted > remainingCap) {
                        claim.setPaidAmount(remainingCap);
                    }
                }
            }
            case cancel -> {
                claim.setPaidAmount(0);
                claim.setClaimStatus(ClaimStatus.rejected);
            }
        }
        claim = claimRepository.save(claim);
        enqueueClaimChanged(claim);
        if (claim.getClaimStatus() == ClaimStatus.approved) {
            enqueueClaimSettled(claim);
        }
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
    public PageResponse<ClaimResponse> myClaims(String keycloakSubject, int page, int size) {
        UUID customerId = CustomerId.fromSubject(keycloakSubject);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<Claim> claims = claimRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        return PageResponse.from(claims.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public ClaimResponse getClaim(String keycloakSubject, UUID claimId, boolean isAdmin) {
        Claim claim = findClaim(claimId);
        if (!isAdmin) {
            UUID customerId = CustomerId.fromSubject(keycloakSubject);
            if (!claim.getCustomerId().equals(customerId)) {
                throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Claim not found", null);
            }
        }
        return toResponse(claim);
    }

    @Transactional(readOnly = true)
    public PageResponse<ClaimResponse> adminListClaims(ClaimStatus status, UUID customerId, UUID policyId,
                                                        int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<Claim> claims = claimRepository.findAdminFiltered(status, customerId, policyId, pageable);
        return PageResponse.from(claims.map(this::toResponse));
    }

    private Claim findClaim(UUID claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Claim not found", null));
    }

    private void enqueueClaimSubmitted(Claim claim) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claim_id", claim.getClaimId().toString());
        payload.put("policy_id", claim.getPolicyId().toString());
        payload.put("customer_id", claim.getCustomerId().toString());
        payload.put("loss_type", claim.getLossType());
        payload.put("occurrence_date", claim.getOccurrenceDate().toString());
        payload.put("estimated_cost", claim.getEstimatedCost() != null ? claim.getEstimatedCost() : 0);
        try {
            outboxPublisher.enqueue("ClaimSubmitted", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue ClaimSubmitted", e);
        }
    }

    private void enqueueClaimChanged(Claim claim) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claim_id", claim.getClaimId().toString());
        payload.put("policy_id", claim.getPolicyId().toString());
        payload.put("customer_id", claim.getCustomerId().toString());
        payload.put("status", claim.getClaimStatus().name());
        payload.put("incurred_amount_vnd", claim.getIncurredAmount());
        payload.put("paid_amount_vnd", claim.getPaidAmount());
        if (claim.getAdminNote() != null) {
            payload.put("admin_note", claim.getAdminNote());
        }
        if (claim.getMisrepresentationSanction() != null) {
            payload.put("misrepresentation_sanction", claim.getMisrepresentationSanction().name());
        }
        try {
            outboxPublisher.enqueue("ClaimStatusChanged", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue ClaimStatusChanged", e);
        }
    }

    private void enqueueClaimSettled(Claim claim) {
        String quoteId = claim.getQuoteId() != null ? claim.getQuoteId().toString() : null;
        String line = findPolicyProjectionOrNull(claim.getPolicyId()) != null ? findPolicyProjectionOrNull(claim.getPolicyId()).getLine() : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claim_id", claim.getClaimId().toString());
        payload.put("policy_id", claim.getPolicyId().toString());
        payload.put("customer_id", claim.getCustomerId().toString());
        payload.put("quote_id", quoteId);
        payload.put("line", line);
        payload.put("exposure_segment_seq", claim.getExposureSegmentSeq());
        payload.put("loss_type", claim.getLossType());
        payload.put("occurrence_date", claim.getOccurrenceDate().toString());
        payload.put("reported_at", claim.getReportDate().toString());
        payload.put("incurred_amount_vnd", claim.getIncurredAmount());
        payload.put("paid_amount_vnd", claim.getPaidAmount());
        payload.put("claim_status", claim.getClaimStatus().name());
        payload.put("settled_at", claim.getPaidAt() != null ? claim.getPaidAt().toString() : OffsetDateTime.now().toString());
        try {
            outboxPublisher.enqueue("ClaimSettled", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue ClaimSettled", e);
        }
    }

    private ClaimPolicyProjection findPolicyProjection(UUID policyId) {
        if (policyProjectionRepository != null) {
            return policyProjectionRepository.findById(policyId)
                    .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        }
        Map<String, Object> policy = compatibilityProjectionReader.getPolicy(policyId);
        ClaimPolicyProjection projection = new ClaimPolicyProjection();
        projection.setPolicyId(policyId);
        projection.setCustomerId(UUID.fromString(String.valueOf(policy.get("customer_id"))));
        projection.setStatus(String.valueOf(policy.get("status")));
        if (policy.get("quote_id") != null) {
            projection.setQuoteId(UUID.fromString(String.valueOf(policy.get("quote_id"))));
        }
        if (policy.get("line") != null) {
            projection.setLine(String.valueOf(policy.get("line")));
        }
        return projection;
    }

    private ClaimPolicyProjection findPolicyProjectionOrNull(UUID policyId) {
        try {
            return findPolicyProjection(policyId);
        } catch (Exception e) {
            return null;
        }
    }

    private List<ClaimExposureSegmentProjection> findSegmentProjections(UUID policyId) {
        if (segmentProjectionRepository != null) {
            return segmentProjectionRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        }
        return compatibilityProjectionReader.getExposureSegments(policyId).stream()
                .map(segment -> toProjection(policyId, segment))
                .toList();
    }

    private ClaimExposureSegmentProjection findSegmentProjection(UUID policyId, int segmentSeq) {
        if (segmentProjectionRepository != null) {
            return segmentProjectionRepository.findByPolicyIdAndExposureSegmentSeq(policyId, segmentSeq)
                    .orElseThrow(() -> new ServiceException(ErrorCode.OCCURRENCE_OUT_OF_COVERAGE));
        }
        return compatibilityProjectionReader.getExposureSegments(policyId).stream()
                .filter(segment -> ((Number) segment.get("exposure_segment_seq")).intValue() == segmentSeq)
                .findFirst()
                .map(segment -> toProjection(policyId, segment))
                .orElseThrow(() -> new ServiceException(ErrorCode.OCCURRENCE_OUT_OF_COVERAGE));
    }

    private ClaimExposureSegmentProjection toProjection(UUID policyId, Map<String, Object> segment) {
        ClaimExposureSegmentProjection projection = new ClaimExposureSegmentProjection();
        projection.setPolicyId(policyId);
        projection.setExposureSegmentSeq(((Number) segment.get("exposure_segment_seq")).intValue());
        projection.setSegmentStart(OffsetDateTime.parse(String.valueOf(segment.get("segment_start"))));
        projection.setSegmentEnd(OffsetDateTime.parse(String.valueOf(segment.get("segment_end"))));
        projection.setCoverageAmountVnd(((Number) segment.get("coverage_amount_vnd")).longValue());
        projection.setDeductibleVnd(((Number) segment.get("deductible_vnd")).longValue());
        return projection;
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
        resp.setIncurredAmount(claim.getIncurredAmount());
        resp.setPaidAmount(claim.getPaidAmount());
        resp.setClaimStatus(claim.getClaimStatus());
        resp.setMisrepresentationSanction(claim.getMisrepresentationSanction());
        resp.setDescription(claim.getDescription());
        resp.setEstimatedCost(claim.getEstimatedCost());
        resp.setAttachments(claim.getAttachments());
        resp.setCreatedAt(claim.getCreatedAt());
        resp.setAdminNote(claim.getAdminNote());
        resp.setPaymentReference(claim.getPaymentReference());
        resp.setPaidAt(claim.getPaidAt());
        return resp;
    }
}
