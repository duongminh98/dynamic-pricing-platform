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

    /**
     * Keys that are NOT risk attributes: changing only these (the priced sum
     * insured / retention) does not require admin review. However, since
     * coverage_amount_vnd and deductible_vnd are model features, every
     * endorsement still re-rates. Any other attribute in the change set is a
     * Material_Change requiring admin review (R23.7, BR-21). This is
     * line-agnostic so a health change (e.g. smoker, age, bmi) is treated the
     * same as a motor change (e.g. vehicle_value_vnd).
     */
    private static final Set<String> NON_MATERIAL_KEYS = Set.of(
            "coverage_amount_vnd", "deductible_vnd");

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
            // However, re-rate immediately so the customer sees the provisional premium.
            long newCoverage = request.getCoverageAmountVnd() != null ? request.getCoverageAmountVnd() : 0L;
            long newDeductible = request.getDeductibleVnd() != null ? request.getDeductibleVnd() : 0L;
            List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
            ExposureSegment prior = segments.isEmpty() ? null : segments.get(segments.size() - 1);
            Map<String, Object> mergedProfile = prior != null ? readRiskSnapshot(prior) : new LinkedHashMap<>();
            mergedProfile.putAll(change);
            mergedProfile.put("coverage_amount_vnd", newCoverage);
            mergedProfile.put("deductible_vnd", newDeductible);
            Long quotedPremium = null;
            try {
                Map<String, Object> requote = pricingClient.rerate(policy.getProductId(), mergedProfile);
                Object premium = requote != null ? requote.get("final_premium_vnd") : null;
                if (premium instanceof Number n) {
                    quotedPremium = n.longValue();
                }
            } catch (RuntimeException e) {
                quotedPremium = null;
            }
            EndorsementRequestEntity pending = new EndorsementRequestEntity();
            pending.setEndorsementRequestId(UUID.randomUUID());
            pending.setPolicyId(policyId);
            pending.setCustomerId(policy.getCustomerId());
            pending.setEffectiveDate(eff);
            pending.setCoverageAmountVnd(request.getCoverageAmountVnd());
            pending.setDeductibleVnd(request.getDeductibleVnd());
            pending.setStatus(EndorsementStatus.PENDING_REVIEW);
            pending.setCreatedAt(OffsetDateTime.now());
            pending.setQuotedPremiumVnd(quotedPremium);
            try {
                pending.setChangeSet(objectMapper.writeValueAsString(change));
            } catch (Exception e) {
                pending.setChangeSet("{}");
            }
            endorsementRequestRepository.save(pending);
            return EndorsementResult.pendingReview(pending.getEndorsementRequestId(), quotedPremium);
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

    /** Read the full risk profile stored on an exposure segment (the re-rate base). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readRiskSnapshot(ExposureSegment segment) {
        String snapshot = segment.getRiskSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(snapshot, Map.class);
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

        // Build the new effective risk profile by merging the change set onto the full
        // base profile carried by the most recent segment (stamped at issuance and on
        // every prior endorsement). This lets a material change re-rate against the
        // complete product feature set rather than only the changed attributes, and
        // keeps the full profile available for subsequent endorsements (R23.2/R23.8).
        Map<String, Object> mergedProfile = prior != null ? readRiskSnapshot(prior) : new LinkedHashMap<>();
        mergedProfile.putAll(change);
        mergedProfile.put("coverage_amount_vnd", newCoverage);
        mergedProfile.put("deductible_vnd", newDeductible);

        // R23.2/R23.8: always re-rate — coverage/deductible changes affect the
        // premium even though they are non-material (no admin review needed).
        // If pricing is unavailable or rejects the profile, fail safe by keeping
        // the prior premium instead of blocking the endorsement — mirrors the
        // renewal re-rate fallback (R24.2).
        try {
            Map<String, Object> requote = pricingClient.rerate(policy.getProductId(), mergedProfile);
            Object premium = requote != null ? requote.get("final_premium_vnd") : null;
            if (premium instanceof Number n) {
                premiumNew = n.longValue();
                policy.setFinalPremiumVnd(premiumNew);
                policyRepository.save(policy);
            }
        } catch (RuntimeException e) {
            premiumNew = premiumOld;
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
        // Persist the full merged profile (not just the delta) so the next endorsement
        // can re-rate against the complete, up-to-date feature set.
        try {
            seg.setRiskSnapshot(objectMapper.writeValueAsString(mergedProfile));
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
                "order_id", policy.getOrderId().toString(),
                "premium_old", premiumOld, "premium_new", premiumNew,
                "remaining_days", remainingDays, "term_days", termDays));
        return toResponse(policy);
    }

    private boolean isMaterialChange(Map<String, Object> change) {
        if (change == null || change.isEmpty()) {
            return false;
        }
        // Any risk attribute (anything other than pure coverage/deductible) is material.
        for (String key : change.keySet()) {
            if (!NON_MATERIAL_KEYS.contains(key)) {
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

        // Retrieve the full risk profile from the old policy's latest exposure segment
        // so the renewal re-rate uses the complete feature set, not just renewal context.
        List<ExposureSegment> oldSegments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment oldLatest = oldSegments.isEmpty() ? null : oldSegments.get(oldSegments.size() - 1);
        Map<String, Object> profile = oldLatest != null ? readRiskSnapshot(oldLatest) : new LinkedHashMap<>();
        profile.put("is_renewal", true);
        profile.put("renewal_number", old.getRenewalNumber() + 1);
        profile.put("years_since_first_policy", old.getYearsSinceFirstPolicy() + 1);
        profile.put("policy_count_prior", old.getPolicyCountPrior() + 1);

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

        // R24.2: re-rate the renewal with the full risk profile + renewal context.
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

        // Stamp exposure segment 0 for the renewed policy with the full risk profile
        // so subsequent endorsements can merge against it.
        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(renewed.getPolicyId());
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(newEff);
        seg.setSegmentEnd(newExp);
        long days = ChronoUnit.DAYS.between(newEff, newExp);
        seg.setEarnedExposureYears(days / 365.25);
        seg.setCoverageAmountVnd(oldLatest != null ? oldLatest.getCoverageAmountVnd() : 0L);
        seg.setDeductibleVnd(oldLatest != null ? oldLatest.getDeductibleVnd() : 0L);
        try {
            seg.setRiskSnapshot(objectMapper.writeValueAsString(profile));
        } catch (Exception e) {
            seg.setRiskSnapshot("{}");
        }
        segmentRepository.save(seg);

        enqueueEvent("PolicyRenewed", renewed.getPolicyId(), Map.of(
                "customer_id", renewed.getCustomerId().toString(),
                "order_id", renewed.getOrderId().toString(),
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
