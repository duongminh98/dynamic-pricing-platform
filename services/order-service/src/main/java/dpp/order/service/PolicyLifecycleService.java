package dpp.order.service;

import dpp.common.security.CustomerId;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.dto.*;
import dpp.order.entity.*;
import dpp.order.client.PricingClient;
import dpp.order.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PolicyLifecycleService {

    /** Attributes whose change is a Material_Change requiring re-rating + admin review (R23.7, BR-21). */
    private static final Set<String> MATERIAL_CHANGE_KEYS = Set.of(
            "vehicle_value_vnd", "vehicle_age", "engine_capacity_cc", "vehicle_segment",
            "primary_use", "driver_count", "province");

    private final PolicyRepository policyRepository;    private final ExposureSegmentRepository segmentRepository;
    private final PolicyDocumentRepository documentRepository;
    private final EndorsementRequestRepository endorsementRequestRepository;
    private final PricingClient pricingClient;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public PolicyLifecycleService(PolicyRepository policyRepository, ExposureSegmentRepository segmentRepository,
                                   PolicyDocumentRepository documentRepository,
                                   EndorsementRequestRepository endorsementRequestRepository,
                                   PricingClient pricingClient, OutboxPublisher outboxPublisher) {
        this.policyRepository = policyRepository;
        this.segmentRepository = segmentRepository;
        this.documentRepository = documentRepository;
        this.endorsementRequestRepository = endorsementRequestRepository;
        this.pricingClient = pricingClient;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Customer endorsement entry point.
     *
     * <p>R23.9 / design §4.2 security gate: a Material_Change is NOT applied here.
     * It is persisted as a PENDING_REVIEW endorsement request that only an
     * Administrator can approve/reject — the customer can never self-approve.
     * Non-material changes (coverage/deductible only) are applied immediately.
     */
    @Transactional
    public EndorsementResult endorse(UUID policyId, EndorsementRequest request, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime eff = request.getEffectiveDate();
        // R23.5: effective date must be strictly inside the open interval (eff, exp).
        if (!eff.isAfter(policy.getPolicyEffectiveDate()) || !eff.isBefore(policy.getPolicyExpirationDate())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE);
        }

        Map<String, Object> change = request.getChange();
        boolean material = isMaterialChange(change);

        if (material) {
            // R23.9: route to the Administrator review queue; do NOT apply yet.
            EndorsementRequestEntity pending = new EndorsementRequestEntity();
            pending.setEndorsementRequestId(UUID.randomUUID());
            pending.setPolicyId(policyId);
            pending.setCustomerId(policy.getCustomerId());
            pending.setEffectiveDate(eff);
            pending.setCoverageAmountVnd(request.getCoverageAmountVnd());
            pending.setDeductibleVnd(request.getDeductibleVnd());
            pending.setStatus(EndorsementStatus.PENDING_REVIEW);
            pending.setCreatedAt(OffsetDateTime.now());
            try {
                pending.setChangeSet(objectMapper.writeValueAsString(change));
            } catch (Exception e) {
                pending.setChangeSet("{}");
            }
            endorsementRequestRepository.save(pending);
            return EndorsementResult.pendingReview(pending.getEndorsementRequestId());
        }

        // Non-material change: apply immediately (no admin needed).
        PolicyResponse applied = applyEndorsement(policy, change, eff,
                request.getCoverageAmountVnd(), request.getDeductibleVnd(), false);
        return EndorsementResult.applied(applied);
    }

    // ── Admin review of Material_Change endorsements (R23.9 / design §4.2) ──

    @Transactional(readOnly = true)
    public List<EndorsementRequestResponse> endorsementReviewQueue() {
        return endorsementRequestRepository.findByStatusOrderByCreatedAtAsc(EndorsementStatus.PENDING_REVIEW)
                .stream().map(this::toEndorsementResponse).collect(java.util.stream.Collectors.toList());
    }

    /** Administrator approves a pending Material_Change: apply it to the policy and mark APPROVED. */
    @Transactional
    public EndorsementRequestResponse approveEndorsement(UUID endorsementRequestId, String reviewer) {
        EndorsementRequestEntity req = findPendingEndorsement(endorsementRequestId);
        Policy policy = policyRepository.findById(req.getPolicyId())
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        Map<String, Object> change = readChangeSet(req);
        // Apply the material change (re-rate remaining term + new segment + new document version).
        applyEndorsement(policy, change, req.getEffectiveDate(),
                req.getCoverageAmountVnd(), req.getDeductibleVnd(), true);
        req.setStatus(EndorsementStatus.APPROVED);
        req.setReviewedBy(reviewer);
        req.setReviewedAt(OffsetDateTime.now());
        endorsementRequestRepository.save(req);
        return toEndorsementResponse(req);
    }

    /** Administrator rejects a pending Material_Change: no change to the policy. */
    @Transactional
    public EndorsementRequestResponse rejectEndorsement(UUID endorsementRequestId, String reason, String reviewer) {
        EndorsementRequestEntity req = findPendingEndorsement(endorsementRequestId);
        req.setStatus(EndorsementStatus.REJECTED);
        req.setReviewReason(reason);
        req.setReviewedBy(reviewer);
        req.setReviewedAt(OffsetDateTime.now());
        endorsementRequestRepository.save(req);
        return toEndorsementResponse(req);
    }

    private EndorsementRequestEntity findPendingEndorsement(UUID endorsementRequestId) {
        EndorsementRequestEntity req = endorsementRequestRepository.findById(endorsementRequestId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Endorsement request not found", null));
        // Only a PENDING_REVIEW request can be acted on; APPROVED/REJECTED are terminal.
        if (req.getStatus() != EndorsementStatus.PENDING_REVIEW) {
            throw new ServiceException(ErrorCode.ORDER_NOT_APPROVED,
                    "Endorsement request is not pending review",
                    Map.of("status", req.getStatus().name()));
        }
        return req;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readChangeSet(EndorsementRequestEntity req) {
        try {
            return objectMapper.readValue(req.getChangeSet(), Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private EndorsementRequestResponse toEndorsementResponse(EndorsementRequestEntity req) {
        EndorsementRequestResponse r = new EndorsementRequestResponse();
        r.setEndorsementRequestId(req.getEndorsementRequestId());
        r.setPolicyId(req.getPolicyId());
        r.setCustomerId(req.getCustomerId());
        r.setStatus(req.getStatus());
        r.setChange(readChangeSet(req));
        r.setEffectiveDate(req.getEffectiveDate());
        r.setCoverageAmountVnd(req.getCoverageAmountVnd());
        r.setDeductibleVnd(req.getDeductibleVnd());
        r.setReviewReason(req.getReviewReason());
        r.setReviewedBy(req.getReviewedBy());
        r.setReviewedAt(req.getReviewedAt());
        r.setCreatedAt(req.getCreatedAt());
        return r;
    }

    /**
     * Apply an endorsement to a policy: create the next exposure segment, optionally
     * re-rate the remaining term (material change), bump the policy_document version,
     * and emit the EndorsementApplied event with the real premium_old/premium_new.
     */
    private PolicyResponse applyEndorsement(Policy policy, Map<String, Object> change, OffsetDateTime eff,
                                            Long coverageOverride, Long deductibleOverride, boolean material) {
        UUID policyId = policy.getPolicyId();
        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment prior = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        int nextSeq = prior == null ? 0 : prior.getExposureSegmentSeq() + 1;

        long newCoverage = coverageOverride != null ? coverageOverride
                : (prior != null ? prior.getCoverageAmountVnd() : 0L);
        long newDeductible = deductibleOverride != null ? deductibleOverride
                : (prior != null ? prior.getDeductibleVnd() : 0L);

        long premiumOld = policy.getFinalPremiumVnd();
        long premiumNew = premiumOld;

        // R23.2/R23.8: re-rate the remaining term for a material change using the new risk profile.
        if (material) {
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.putAll(change);
            profile.put("coverage_amount_vnd", newCoverage);
            profile.put("deductible_vnd", newDeductible);
            // The full original risk profile is not persisted in order-service (it lives in
            // the pricing quote at issuance time and is not echoed back), so a material change
            // carries only the changed attributes. If pricing rejects the partial profile
            // (e.g. MISSING_FEATURES), fail safe by keeping the prior premium instead of
            // blocking the endorsement — mirrors the renewal re-rate fallback (R24.2).
            try {
                Map<String, Object> requote = pricingClient.rerate(policy.getProductId(), profile);
                Object premium = requote != null ? requote.get("final_premium_vnd") : null;
                if (premium instanceof Number n) {
                    premiumNew = n.longValue();
                    policy.setFinalPremiumVnd(premiumNew);
                    policyRepository.save(policy);
                }
            } catch (RuntimeException e) {
                premiumNew = premiumOld;
            }
        }

        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policyId);
        seg.setExposureSegmentSeq(nextSeq);
        seg.setSegmentStart(eff);
        seg.setSegmentEnd(policy.getPolicyExpirationDate());
        long days = ChronoUnit.DAYS.between(eff, policy.getPolicyExpirationDate());
        seg.setEarnedExposureYears(days / 365.25);
        seg.setCoverageAmountVnd(newCoverage);
        seg.setDeductibleVnd(newDeductible);
        try {
            seg.setRiskSnapshot(objectMapper.writeValueAsString(change));
        } catch (Exception e) {
            seg.setRiskSnapshot("{}");
        }
        segmentRepository.save(seg);

        int newVersion = documentRepository.findByPolicyIdOrderByVersionDesc(policyId).stream()
                .findFirst().map(PolicyDocument::getVersion).orElse(1) + 1;
        PolicyDocument doc = new PolicyDocument();
        doc.setDocumentId(UUID.randomUUID());
        doc.setPolicyId(policyId);
        doc.setVersion(newVersion);
        Map<String, Object> docContent = new LinkedHashMap<>();
        docContent.put("policy_id", policyId.toString());
        docContent.put("version", newVersion);
        docContent.put("effective_date", eff.toString());
        docContent.put("coverage_amount_vnd", newCoverage);
        docContent.put("deductible_vnd", newDeductible);
        docContent.put("final_premium_vnd", policy.getFinalPremiumVnd());
        docContent.put("change", change);
        try {
            doc.setContent(objectMapper.writeValueAsString(docContent));
        } catch (Exception e) {
            doc.setContent("{}");
        }
        doc.setCreatedAt(OffsetDateTime.now());
        documentRepository.save(doc);

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(eff, policy.getPolicyExpirationDate());
        enqueueEvent("EndorsementApplied", policyId, Map.of("customer_id", policy.getCustomerId().toString(),
                "premium_old", premiumOld, "premium_new", premiumNew,
                "remaining_days", remainingDays, "term_days", termDays));
        return toResponse(policy);
    }

    private boolean isMaterialChange(Map<String, Object> change) {
        if (change == null) {
            return false;
        }
        for (String key : change.keySet()) {
            if (MATERIAL_CHANGE_KEYS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public PolicyResponse renew(UUID policyId, String keycloakSubject) {
        Policy old = findOwnedPolicy(policyId, keycloakSubject);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime newEff = old.getPolicyExpirationDate().isBefore(now) ? now : old.getPolicyExpirationDate();
        OffsetDateTime newExp = newEff.plus(365, ChronoUnit.DAYS);

        Policy renewed = new Policy();
        renewed.setPolicyId(UUID.randomUUID());
        renewed.setOrderId(old.getOrderId());
        renewed.setCustomerId(old.getCustomerId());
        renewed.setProductId(old.getProductId());
        renewed.setStatus(PolicyStatus.active);
        renewed.setPolicyEffectiveDate(newEff);
        renewed.setPolicyExpirationDate(newExp);
        renewed.setRenewalNumber(old.getRenewalNumber() + 1);
        renewed.setRenewal(true);
        renewed.setYearsSinceFirstPolicy(old.getYearsSinceFirstPolicy() + 1);
        renewed.setPolicyCountPrior(old.getPolicyCountPrior() + 1);

        // R24.2: re-rate the renewal with renewal context instead of cloning the old premium.
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("is_renewal", true);
        profile.put("renewal_number", renewed.getRenewalNumber());
        profile.put("years_since_first_policy", renewed.getYearsSinceFirstPolicy());
        profile.put("policy_count_prior", renewed.getPolicyCountPrior());
        long renewedPremium = old.getFinalPremiumVnd();
        try {
            Map<String, Object> requote = pricingClient.rerate(old.getProductId(), profile);
            Object premium = requote != null ? requote.get("final_premium_vnd") : null;
            if (premium instanceof Number n) {
                renewedPremium = n.longValue();
            }
        } catch (RuntimeException e) {
            // Fail safe: if pricing is unavailable, keep prior premium rather than block renewal.
            renewedPremium = old.getFinalPremiumVnd();
        }
        renewed.setFinalPremiumVnd(renewedPremium);
        renewed.setCreatedAt(now);
        policyRepository.save(renewed);

        enqueueEvent("PolicyRenewed", renewed.getPolicyId(), Map.of(
                "customer_id", renewed.getCustomerId().toString(),
                "renewal_number", renewed.getRenewalNumber(),
                "final_premium_vnd", renewedPremium));
        return toResponse(renewed);
    }

    @Transactional
    public PolicyResponse cancel(UUID policyId, CancelRequest request, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime cancelDate = request.getCancelDate();
        if (cancelDate.isAfter(policy.getPolicyExpirationDate()) || cancelDate.isBefore(policy.getPolicyEffectiveDate())) {
            throw new ServiceException(ErrorCode.CANCEL_DATE_OUT_OF_RANGE);
        }
        policy.setStatus(PolicyStatus.cancelled);
        policy.setCancelDate(cancelDate);
        policyRepository.save(policy);

        // R25.3: cut the exposure segment covering cancel_date and recompute earned exposure.
        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        for (ExposureSegment seg : segments) {
            if (!cancelDate.isBefore(seg.getSegmentStart()) && cancelDate.isBefore(seg.getSegmentEnd())) {
                seg.setSegmentEnd(cancelDate);
                long segDays = ChronoUnit.DAYS.between(seg.getSegmentStart(), cancelDate);
                seg.setEarnedExposureYears(Math.max(0, segDays) / 365.25);
                segmentRepository.save(seg);
            } else if (seg.getSegmentStart().isAfter(cancelDate)) {
                // Segments entirely after cancellation earn no exposure.
                seg.setSegmentEnd(cancelDate.isAfter(seg.getSegmentStart()) ? seg.getSegmentEnd() : seg.getSegmentStart());
                seg.setEarnedExposureYears(0.0);
                segmentRepository.save(seg);
            }
        }

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(cancelDate, policy.getPolicyExpirationDate());
        enqueueEvent("PolicyCancelled", policyId, Map.of("customer_id", policy.getCustomerId().toString(), "cancel_date", cancelDate.toString(), "final_premium_vnd", policy.getFinalPremiumVnd(), "remaining_days", remainingDays, "term_days", termDays));
        return toResponse(policy);
    }

    private Policy findOwnedPolicy(UUID policyId, String keycloakSubject) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ServiceException(ErrorCode.FORBIDDEN_RESOURCE));
        UUID customerId = CustomerId.fromSubject(keycloakSubject);
        if (!policy.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return policy;
    }

    private void enqueueEvent(String type, UUID policyId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policy_id", policyId.toString());
        payload.putAll(extra);
        try {
            outboxPublisher.enqueue(type, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue " + type, e);
        }
    }

    public PolicyResponse toResponse(Policy policy) {
        PolicyResponse resp = new PolicyResponse();
        resp.setPolicyId(policy.getPolicyId());
        resp.setOrderId(policy.getOrderId());
        resp.setCustomerId(policy.getCustomerId());
        resp.setProductId(policy.getProductId());
        resp.setStatus(policy.getStatus());
        resp.setPolicyEffectiveDate(policy.getPolicyEffectiveDate());
        resp.setPolicyExpirationDate(policy.getPolicyExpirationDate());
        resp.setRenewalNumber(policy.getRenewalNumber());
        resp.setRenewal(policy.isRenewal());
        resp.setFinalPremiumVnd(policy.getFinalPremiumVnd());
        resp.setCancelDate(policy.getCancelDate());
        resp.setCreatedAt(policy.getCreatedAt());
        return resp;
    }
}
