package dpp.claims.service;

import dpp.claims.client.OrderClient;
import dpp.claims.dto.*;
import dpp.claims.entity.*;
import dpp.claims.repository.ClaimRepository;
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
        UUID policyOwner = UUID.fromString(String.valueOf(policy.get("customerId")));
        UUID customerId = UUID.nameUUIDFromBytes(keycloakSubject.getBytes());
        if (!policyOwner.equals(customerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }

        OffsetDateTime occurrence = request.getOccurrenceDate();
        OffsetDateTime reportDate = OffsetDateTime.now();
        if (reportDate.isBefore(occurrence)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Report date before occurrence", null);
        }

        int segmentSeq = findSegmentSeq(policy, occurrence);

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

    private int findSegmentSeq(Map<String, Object> policy, OffsetDateTime occurrence) {
        OffsetDateTime eff = OffsetDateTime.parse(String.valueOf(policy.get("policyEffectiveDate")));
        OffsetDateTime exp = OffsetDateTime.parse(String.valueOf(policy.get("policyExpirationDate")));
        if (occurrence.isBefore(eff) || occurrence.isAfter(exp)) {
            throw new ServiceException(ErrorCode.OCCURRENCE_OUT_OF_COVERAGE);
        }
        return 0;
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
        claim.setMisrepresentationSanction(MisrepresentationSanction.valueOf(request.getSanction()));
        if (request.getSanction().equals("reject")) {
            claim.setPaidAmount(0);
        }
        claim = claimRepository.save(claim);
        return toResponse(claim);
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> myClaims(String keycloakSubject) {
        UUID customerId = UUID.nameUUIDFromBytes(keycloakSubject.getBytes());
        return claimRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ClaimResponse getClaim(String keycloakSubject, UUID claimId) {
        Claim claim = findClaim(claimId);
        UUID customerId = UUID.nameUUIDFromBytes(keycloakSubject.getBytes());
        if (!claim.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return toResponse(claim);
    }

    private Claim findClaim(UUID claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new ServiceException(ErrorCode.BAD_REQUEST, "Claim not found", null));
    }

    private void enqueueClaimChanged(Claim claim) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claim_id", claim.getClaimId().toString());
        payload.put("policy_id", claim.getPolicyId().toString());
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
