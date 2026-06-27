package dpp.order.service;

import dpp.common.security.CustomerId;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.dto.*;
import dpp.order.entity.*;
import dpp.order.client.PricingClient;
import dpp.order.client.BillingClient;
import dpp.order.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final Set<String> BLOCKED_KEYS = Set.of(
            "coverage_amount_vnd", "deductible_vnd");

    static final int ENDORSEMENT_PAYMENT_DUE_DAYS = 14;
    static final long MIN_SETTLE_AMOUNT = 10_000L;

    private static final Map<String, Set<String>> ALLOWED_KEYS_BY_LINE = Map.of(
            "health", Set.of("height_cm", "weight_kg", "bmi", "smoker", "chronic_disease",
                    "diabetes", "blood_pressure_problem", "major_surgeries_count",
                    "hospitalized_last_12m", "medical_visit_count_12m"),
            "motorbike", Set.of("vehicle_brand", "vehicle_model", "vehicle_segment",
                    "vehicle_age", "vehicle_value_vnd", "engine_capacity_cc",
                    "driving_experience_years", "annual_mileage_km",
                    "traffic_violation_count_12m", "parking_location",
                    "anti_theft_device", "primary_use"),
            "car", Set.of("vehicle_brand", "vehicle_model", "vehicle_segment",
                    "vehicle_age", "vehicle_value_vnd", "engine_capacity_cc",
                    "driving_experience_years", "annual_mileage_km",
                    "traffic_violation_count_12m", "parking_location",
                    "anti_theft_device", "primary_use",
                    "driver_count", "garage_repair_option", "loan_or_leasing_flag"),
            "home", Set.of("property_type", "floor_area_m2", "number_of_floors",
                    "building_age", "construction_type", "roof_type",
                    "flood_risk_zone", "fire_protection", "has_fire_alarm",
                    "has_sprinkler", "security_system", "declared_property_value_vnd"),
            "accident", Set.of("occupation_class", "workplace_risk_level",
                    "commute_mode", "commute_distance_km", "sport_activity_flag",
                    "sport_risk_level", "hazardous_activity_exclusion_flag"),
            "travel", Set.of("domestic_or_international", "destination_region",
                    "destination_country", "trip_duration_days", "traveler_count",
                    "trip_cost_vnd", "travel_purpose", "has_baggage_cover",
                    "has_trip_cancellation_cover")
    );

    private final PolicyRepository policyRepository;    private final ExposureSegmentRepository segmentRepository;
    private final PolicyDocumentRepository documentRepository;
    private final EndorsementRequestRepository endorsementRequestRepository;
    private final PricingClient pricingClient;
    private final BillingClient billingClient;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public PolicyLifecycleService(PolicyRepository policyRepository, ExposureSegmentRepository segmentRepository,
                                   PolicyDocumentRepository documentRepository,
                                   EndorsementRequestRepository endorsementRequestRepository,
                                   PricingClient pricingClient, BillingClient billingClient,
                                   OutboxPublisher outboxPublisher) {
        this.policyRepository = policyRepository;
        this.segmentRepository = segmentRepository;
        this.documentRepository = documentRepository;
        this.endorsementRequestRepository = endorsementRequestRepository;
        this.pricingClient = pricingClient;
        this.billingClient = billingClient;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Customer endorsement entry point.
     *
     * <p>Every endorsement is a Material_Change: it is persisted as a
     * PENDING_REVIEW endorsement request that only an Administrator can
     * approve/reject — the customer can never self-approve.
     */
    /**
     * Preview an endorsement: re-rate without saving anything. Returns the current
     * premium, the quoted premium for the proposed change, whether it is material,
     * and the difference. The customer can review this before deciding to submit.
     */
    @Transactional(readOnly = true)
    public EndorsementPreviewResponse previewEndorsement(UUID policyId, EndorsementRequest request, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime eff = request.getEffectiveDate();
        if (!eff.isAfter(policy.getPolicyEffectiveDate()) || !eff.isBefore(policy.getPolicyExpirationDate())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE);
        }
        if (eff.isBefore(OffsetDateTime.now())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE,
                    "Effective date cannot be in the past", null);
        }

        Map<String, Object> change = request.getChange();
        validateChangeKeys(change, policy.getProductId());

        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment prior = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        long newCoverage = prior != null ? prior.getCoverageAmountVnd() : 0L;
        long newDeductible = prior != null ? prior.getDeductibleVnd() : 0L;
        Map<String, Object> mergedProfile = prior != null ? readRiskSnapshot(prior) : new LinkedHashMap<>();
        mergedProfile.putAll(change);
        mergedProfile.put("coverage_amount_vnd", newCoverage);
        mergedProfile.put("deductible_vnd", newDeductible);

        long currentPremium = policy.getFinalPremiumVnd();
        long quotedPremium = currentPremium;
        try {
            Map<String, Object> requote = pricingClient.rerate(policy.getProductId(), mergedProfile);
            Object premium = requote != null ? requote.get("final_premium_vnd") : null;
            if (premium instanceof Number n) {
                quotedPremium = n.longValue();
            }
        } catch (RuntimeException e) {
            quotedPremium = currentPremium;
        }

        EndorsementPreviewResponse resp = new EndorsementPreviewResponse();
        resp.setPolicyId(policyId);
        resp.setEffectiveDate(eff);
        resp.setMaterialChange(true);
        resp.setCurrentPremiumVnd(currentPremium);
        resp.setQuotedPremiumVnd(quotedPremium);
        resp.setDifferenceVnd(quotedPremium - currentPremium);
        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(eff, policy.getPolicyExpirationDate());
        double fraction = termDays > 0 ? remainingDays / (double) termDays : 0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        long proRated = Math.round((quotedPremium - currentPremium) * fraction);
        resp.setProRatedChargeVnd(proRated);
        resp.setRemainingDays(remainingDays);
        resp.setTermDays(termDays);
        resp.setCoverageAmountVnd(newCoverage);
        resp.setDeductibleVnd(newDeductible);
        return resp;
    }

    @Transactional
    public EndorsementResult endorse(UUID policyId, EndorsementRequest request, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime eff = request.getEffectiveDate();
        if (!eff.isAfter(policy.getPolicyEffectiveDate()) || !eff.isBefore(policy.getPolicyExpirationDate())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE);
        }
        if (eff.isBefore(OffsetDateTime.now())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE,
                    "Effective date cannot be in the past", null);
        }

        Map<String, Object> change = request.getChange();
        validateChangeKeys(change, policy.getProductId());

        // A4: Block concurrent endorsement — at most one in-progress per policy.
        List<EndorsementRequestEntity> existing = endorsementRequestRepository.findByPolicyIdOrderByCreatedAtDesc(policyId);
        for (EndorsementRequestEntity e : existing) {
            if (e.getStatus() == EndorsementStatus.PENDING_REVIEW
                    || e.getStatus() == EndorsementStatus.APPROVED_PENDING_PAYMENT) {
                throw new ServiceException(ErrorCode.ENDORSEMENT_IN_PROGRESS,
                        "An endorsement is already in progress for this policy",
                        Map.of("endorsement_request_id", e.getEndorsementRequestId().toString()));
            }
        }

        // Re-rate immediately so the customer sees the provisional premium.
        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment prior = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        long newCoverage = prior != null ? prior.getCoverageAmountVnd() : 0L;
        long newDeductible = prior != null ? prior.getDeductibleVnd() : 0L;
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
        pending.setStatus(EndorsementStatus.PENDING_REVIEW);
        pending.setCreatedAt(OffsetDateTime.now());
        pending.setQuotedPremiumVnd(quotedPremium);
        try {
            pending.setChangeSet(objectMapper.writeValueAsString(change));
        } catch (Exception e) {
            pending.setChangeSet("{}");
        }
        endorsementRequestRepository.save(pending);

        long currentPremium = policy.getFinalPremiumVnd();
        long difference = quotedPremium != null ? quotedPremium - currentPremium : 0L;
        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(eff, policy.getPolicyExpirationDate());
        double fraction = termDays > 0 ? remainingDays / (double) termDays : 0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        long proRated = Math.round(difference * fraction);

        enqueueEvent("EndorsementSubmitted", policyId, Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "policy_id", policyId.toString(),
                "endorsement_request_id", pending.getEndorsementRequestId().toString(),
                "effective_date", eff.toString(),
                "difference_vnd", difference,
                "pro_rated_charge_vnd", proRated));

        return EndorsementResult.pendingReview(pending.getEndorsementRequestId(), quotedPremium,
                difference, proRated, eff, pending.getCreatedAt());
    }

    // ── Admin review of Material_Change endorsements (R23.9 / design §4.2) ──

    @Transactional(readOnly = true)
    public List<PolicyResponse> adminListAllPolicies() {
        return policyRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<PolicyResponse> adminListPoliciesPaged(PolicyStatus status, UUID customerId,
                                                                String line, org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<Policy> page = policyRepository.findFiltered(status, customerId, line, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public List<PolicyResponse> adminListPoliciesByStatus(PolicyStatus status) {
        return policyRepository.findByStatusOrderByCreatedAtDesc(status)
                .stream().map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PolicyResponse adminGetPolicy(UUID policyId) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        return toResponse(policy);
    }

    @Transactional(readOnly = true)
    public PolicyDetailResponse adminGetPolicyDetail(UUID policyId) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));

        PolicyDetailResponse resp = new PolicyDetailResponse();
        resp.setPolicyId(policy.getPolicyId());
        resp.setOrderId(policy.getOrderId());
        resp.setCustomerId(policy.getCustomerId());
        resp.setProductId(policy.getProductId());
        resp.setLine(policy.getLine());
        resp.setStatus(policy.getStatus());
        resp.setPolicyEffectiveDate(policy.getPolicyEffectiveDate());
        resp.setPolicyExpirationDate(policy.getPolicyExpirationDate());
        resp.setRenewalNumber(policy.getRenewalNumber());
        resp.setRenewal(policy.isRenewal());
        resp.setYearsSinceFirstPolicy(policy.getYearsSinceFirstPolicy());
        resp.setPolicyCountPrior(policy.getPolicyCountPrior());
        resp.setFinalPremiumVnd(policy.getFinalPremiumVnd());
        resp.setAssetKey(policy.getAssetKey());
        resp.setCancelDate(policy.getCancelDate());
        resp.setCreatedAt(policy.getCreatedAt());

        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        resp.setExposureSegments(segments.stream().map(seg -> {
            ExposureSegmentResponse s = new ExposureSegmentResponse();
            s.setSegmentId(seg.getSegmentId());
            s.setPolicyId(seg.getPolicyId());
            s.setExposureSegmentSeq(seg.getExposureSegmentSeq());
            s.setSegmentStart(seg.getSegmentStart());
            s.setSegmentEnd(seg.getSegmentEnd());
            s.setEarnedExposureYears(seg.getEarnedExposureYears());
            s.setCoverageAmountVnd(seg.getCoverageAmountVnd());
            s.setDeductibleVnd(seg.getDeductibleVnd());
            return s;
        }).collect(java.util.stream.Collectors.toList()));

        resp.setEndorsements(endorsementRequestRepository.findByPolicyIdOrderByCreatedAtDesc(policyId)
                .stream().map(this::toEndorsementResponse).collect(java.util.stream.Collectors.toList()));

        resp.setDocuments(documentRepository.findByPolicyIdOrderByVersionDesc(policyId)
                .stream().map(doc -> {
                    PolicyDocumentResponse d = new PolicyDocumentResponse();
                    d.setDocumentId(doc.getDocumentId());
                    d.setPolicyId(doc.getPolicyId());
                    d.setVersion(doc.getVersion());
                    d.setContent(doc.getContent());
                    d.setCreatedAt(doc.getCreatedAt());
                    return d;
                }).collect(java.util.stream.Collectors.toList()));

        return resp;
    }

    @Transactional
    public PolicyResponse adminCancelPolicy(UUID policyId, OffsetDateTime cancelDate) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        if (cancelDate == null) {
            cancelDate = OffsetDateTime.now();
        }
        if (cancelDate.isAfter(policy.getPolicyExpirationDate()) || cancelDate.isBefore(policy.getPolicyEffectiveDate())) {
            throw new ServiceException(ErrorCode.CANCEL_DATE_OUT_OF_RANGE);
        }
        policy.setStatus(PolicyStatus.cancelled);
        policy.setCancelDate(cancelDate);
        policyRepository.save(policy);

        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        for (ExposureSegment seg : segments) {
            if (!cancelDate.isBefore(seg.getSegmentStart()) && cancelDate.isBefore(seg.getSegmentEnd())) {
                seg.setSegmentEnd(cancelDate);
                long segDays = ChronoUnit.DAYS.between(seg.getSegmentStart(), cancelDate);
                seg.setEarnedExposureYears(Math.max(0, segDays) / 365.25);
                segmentRepository.save(seg);
            } else if (seg.getSegmentStart().isAfter(cancelDate)) {
                seg.setSegmentEnd(seg.getSegmentStart());
                seg.setEarnedExposureYears(0.0);
                segmentRepository.save(seg);
            }
        }

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(cancelDate, policy.getPolicyExpirationDate());
        enqueueEvent("PolicyCancelled", policyId, Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "cancel_date", cancelDate.toString(),
                "final_premium_vnd", policy.getFinalPremiumVnd(),
                "remaining_days", remainingDays,
                "term_days", termDays));
        return toResponse(policy);
    }

    @Transactional(readOnly = true)
    public List<EndorsementRequestResponse> endorsementReviewQueue() {
        return endorsementRequestRepository.findByStatusOrderByCreatedAtAsc(EndorsementStatus.PENDING_REVIEW)
                .stream().map(this::toEndorsementResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<EndorsementRequestResponse> adminEndorsementQueuePaged(
            EndorsementStatus status, UUID customerId, UUID policyId,
            org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<EndorsementRequestEntity> page =
                endorsementRequestRepository.findFiltered(status, customerId, policyId, pageable);
        return PageResponse.from(page.map(this::toEndorsementResponse));
    }

    @Transactional(readOnly = true)
    public EndorsementRequestResponse adminGetEndorsementDetail(UUID endorsementRequestId) {
        EndorsementRequestEntity req = endorsementRequestRepository.findById(endorsementRequestId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Endorsement not found", null));
        return toEndorsementResponse(req);
    }

    @Transactional(readOnly = true)
    public List<EndorsementRequestResponse> pendingPaymentQueue() {
        return endorsementRequestRepository.findByStatusOrderByDueDateAsc(EndorsementStatus.APPROVED_PENDING_PAYMENT)
                .stream().map(this::toEndorsementResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EndorsementRequestResponse> voidedEndorsements() {
        return endorsementRequestRepository.findByStatusOrderByCreatedAtAsc(EndorsementStatus.VOID)
                .stream().map(this::toEndorsementResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EndorsementRequestResponse> policyEndorsements(UUID policyId, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        return endorsementRequestRepository.findByPolicyIdOrderByCreatedAtDesc(policyId)
                .stream().map(this::toEndorsementResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<EndorsementRequestResponse> policyEndorsementsPaged(UUID policyId, String keycloakSubject,
                                                                             org.springframework.data.domain.Pageable pageable) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        org.springframework.data.domain.Page<EndorsementRequestEntity> page =
                endorsementRequestRepository.findByPolicyIdOrderByCreatedAtDesc(policyId, pageable);
        return PageResponse.from(page.map(this::toEndorsementResponse));
    }

    @Transactional(readOnly = true)
    public EndorsementRequestResponse getEndorsement(UUID policyId, UUID endorsementRequestId, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        EndorsementRequestEntity req = endorsementRequestRepository.findById(endorsementRequestId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Endorsement not found", null));
        if (!req.getPolicyId().equals(policyId)) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Endorsement not found", null);
        }
        return toEndorsementResponse(req);
    }

    /** A5: Customer self-cancel an endorsement in PENDING_REVIEW or APPROVED_PENDING_PAYMENT. */
    @Transactional
    public EndorsementCancelResponse cancelEndorsement(UUID policyId, UUID endorsementRequestId,
                                                        String keycloakSubject, String reason) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        EndorsementRequestEntity req = endorsementRequestRepository.findById(endorsementRequestId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Endorsement not found", null));
        if (!req.getPolicyId().equals(policyId)) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Endorsement not found", null);
        }
        if (req.getStatus() != EndorsementStatus.PENDING_REVIEW
                && req.getStatus() != EndorsementStatus.APPROVED_PENDING_PAYMENT) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_NOT_CANCELLABLE,
                    "Endorsement cannot be cancelled in its current state",
                    Map.of("status", req.getStatus().name()));
        }

        boolean invoiceVoided = false;
        if (req.getStatus() == EndorsementStatus.APPROVED_PENDING_PAYMENT && req.getInvoiceId() != null) {
            try {
                billingClient.voidInvoiceByEndorsement(endorsementRequestId);
                invoiceVoided = true;
            } catch (Exception ignored) {
            }
        }

        OffsetDateTime cancelledAt = OffsetDateTime.now();
        req.setStatus(EndorsementStatus.CANCELLED);
        req.setReviewReason(reason);
        req.setReviewedAt(cancelledAt);
        endorsementRequestRepository.save(req);

        enqueueEvent("EndorsementCancelled", policyId, Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "policy_id", policyId.toString(),
                "endorsement_request_id", endorsementRequestId.toString(),
                "policy_changed", false));

        EndorsementCancelResponse resp = new EndorsementCancelResponse();
        resp.setEndorsementRequestId(endorsementRequestId);
        resp.setPolicyId(policyId);
        resp.setStatus(EndorsementStatus.CANCELLED);
        resp.setCancelledAt(cancelledAt);
        resp.setInvoiceVoided(invoiceVoided);
        resp.setPolicyChanged(false);
        return resp;
    }

    /** Administrator approves a pending Material_Change.
     *  AP (premium increase): net-off credits first, then gate by payment if netDue >= MIN_SETTLE_AMOUNT.
     *  RP (premium decrease): apply immediately, issue credit if returnPremium >= MIN_SETTLE_AMOUNT.
     *  Terminal state for applied endorsements is APPLIED. */
    @Transactional
    public EndorsementRequestResponse approveEndorsement(UUID endorsementRequestId, String reviewer) {
        EndorsementRequestEntity req = findPendingEndorsement(endorsementRequestId);
        Policy policy = policyRepository.findById(req.getPolicyId())
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        Map<String, Object> change = readChangeSet(req);

        long currentPremium = policy.getFinalPremiumVnd();
        long quotedPremium = req.getQuotedPremiumVnd() != null ? req.getQuotedPremiumVnd() : currentPremium;

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(req.getEffectiveDate(), policy.getPolicyExpirationDate());
        double fraction = termDays > 0 ? remainingDays / (double) termDays : 0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        if (quotedPremium > currentPremium) {
            // Premium increase (AP): calculate pro-rata additional charge.
            long additionalCharge = Math.round((quotedPremium - currentPremium) * fraction);

            // Net-off: apply available credits first via billing service.
            Map<String, Object> creditResp = billingClient.applyCreditAndQuote(
                    policy.getPolicyId(), additionalCharge);
            long creditApplied = creditResp != null && creditResp.get("credit_applied_vnd") != null
                    ? Long.parseLong(String.valueOf(creditResp.get("credit_applied_vnd"))) : 0;
            long netDue = creditResp != null && creditResp.get("net_due_vnd") != null
                    ? Long.parseLong(String.valueOf(creditResp.get("net_due_vnd"))) : additionalCharge;

            if (netDue >= MIN_SETTLE_AMOUNT) {
                // Create invoice for the net amount due after credit application.
                OffsetDateTime dueDate = OffsetDateTime.now().plusDays(ENDORSEMENT_PAYMENT_DUE_DAYS);
                Map<String, Object> invoiceResp = billingClient.createEndorsementInvoice(
                        policy.getOrderId(), policy.getPolicyId(), netDue,
                        endorsementRequestId, dueDate);
                UUID invoiceId = null;
                if (invoiceResp != null && invoiceResp.get("invoice_id") != null) {
                    invoiceId = UUID.fromString(String.valueOf(invoiceResp.get("invoice_id")));
                }

                req.setStatus(EndorsementStatus.APPROVED_PENDING_PAYMENT);
                req.setReviewedBy(reviewer);
                req.setReviewedAt(OffsetDateTime.now());
                req.setInvoiceId(invoiceId);
                req.setDueDate(OffsetDateTime.now().plusDays(ENDORSEMENT_PAYMENT_DUE_DAYS));
                endorsementRequestRepository.save(req);

                enqueueEvent("EndorsementPendingPayment", policy.getPolicyId(), Map.of(
                        "customer_id", policy.getCustomerId().toString(),
                        "endorsement_request_id", endorsementRequestId.toString(),
                        "invoice_id", invoiceId != null ? invoiceId.toString() : "",
                        "additional_charge_vnd", netDue,
                        "due_date", req.getDueDate() != null ? req.getDueDate().toString() : ""));
            } else {
                // Net due below threshold: waive, apply endorsement immediately.
                applyEndorsement(policy, change, req.getEffectiveDate(), true, quotedPremium);
                req.setStatus(EndorsementStatus.APPLIED);
                req.setReviewedBy(reviewer);
                req.setReviewedAt(OffsetDateTime.now());
                endorsementRequestRepository.save(req);
            }
        } else {
            // Premium decrease or no change (RP): apply immediately.
            applyEndorsement(policy, change, req.getEffectiveDate(), true, quotedPremium);
            req.setStatus(EndorsementStatus.APPLIED);
            req.setReviewedBy(reviewer);
            req.setReviewedAt(OffsetDateTime.now());
            endorsementRequestRepository.save(req);

            // Issue credit if return premium is above de minimis threshold.
            long returnPremium = Math.round((currentPremium - quotedPremium) * fraction);
            if (returnPremium >= MIN_SETTLE_AMOUNT) {
                enqueueEvent("EndorsementCreditIssued", policy.getPolicyId(), Map.of(
                        "customer_id", policy.getCustomerId().toString(),
                        "endorsement_request_id", endorsementRequestId.toString(),
                        "amount_vnd", returnPremium));
            }
        }
        return toEndorsementResponse(req);
    }

    /** Called when an adjustment invoice is paid — applies the held endorsement. */
    @Transactional
    public void applyPendingEndorsement(UUID endorsementRequestId) {
        EndorsementRequestEntity req = endorsementRequestRepository.findById(endorsementRequestId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Endorsement not found", null));
        if (req.getStatus() != EndorsementStatus.APPROVED_PENDING_PAYMENT) {
            return;
        }
        Policy policy = policyRepository.findById(req.getPolicyId())
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        Map<String, Object> change = readChangeSet(req);
        applyEndorsement(policy, change, req.getEffectiveDate(), true,
                req.getQuotedPremiumVnd());
        req.setStatus(EndorsementStatus.APPLIED);
        endorsementRequestRepository.save(req);
    }

    /** Administrator extends the payment deadline for an APPROVED_PENDING_PAYMENT endorsement.
     *  Also revives a VOID endorsement back to APPROVED_PENDING_PAYMENT with a new invoice. */
    @Transactional
    public EndorsementRequestResponse extendDueDate(UUID endorsementRequestId, int extraDays) {
        EndorsementRequestEntity req = endorsementRequestRepository.findById(endorsementRequestId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Endorsement not found", null));
        if (req.getStatus() != EndorsementStatus.APPROVED_PENDING_PAYMENT
                && req.getStatus() != EndorsementStatus.VOID) {
            throw new ServiceException(ErrorCode.ORDER_NOT_APPROVED,
                    "Only APPROVED_PENDING_PAYMENT or VOID endorsements can be extended",
                    Map.of("status", req.getStatus().name()));
        }

        Policy policy = policyRepository.findById(req.getPolicyId())
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));

        // Void old invoice if it exists (unpaid invoices only; paid ones are left alone).
        if (req.getInvoiceId() != null) {
            try {
                billingClient.voidInvoiceByEndorsement(endorsementRequestId);
            } catch (Exception ignored) {
            }
        }

        // Recalculate additional charge (same pro-rata logic as approveEndorsement).
        long currentPremium = policy.getFinalPremiumVnd();
        long quotedPremium = req.getQuotedPremiumVnd() != null ? req.getQuotedPremiumVnd() : currentPremium;
        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(req.getEffectiveDate(), policy.getPolicyExpirationDate());
        double fraction = termDays > 0 ? remainingDays / (double) termDays : 0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        long additionalCharge = Math.round((quotedPremium - currentPremium) * fraction);

        OffsetDateTime newDueDate = OffsetDateTime.now().plusDays(extraDays);
        Map<String, Object> invoiceResp = billingClient.createEndorsementInvoice(
                policy.getOrderId(), policy.getPolicyId(), additionalCharge,
                endorsementRequestId, newDueDate);
        UUID newInvoiceId = null;
        if (invoiceResp != null && invoiceResp.get("invoice_id") != null) {
            newInvoiceId = UUID.fromString(String.valueOf(invoiceResp.get("invoice_id")));
        }

        req.setStatus(EndorsementStatus.APPROVED_PENDING_PAYMENT);
        req.setInvoiceId(newInvoiceId);
        req.setDueDate(newDueDate);
        endorsementRequestRepository.save(req);
        return toEndorsementResponse(req);
    }

    /** Administrator cancels a policy due to overdue endorsement (customer declared risk change but didn't pay). */
    @Transactional
    public PolicyResponse cancelPolicyFromEndorsement(UUID endorsementRequestId, String reviewer) {
        EndorsementRequestEntity req = endorsementRequestRepository.findById(endorsementRequestId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Endorsement not found", null));
        if (req.getStatus() != EndorsementStatus.VOID) {
            throw new ServiceException(ErrorCode.ORDER_NOT_APPROVED,
                    "Only VOID endorsements can trigger policy cancellation",
                    Map.of("status", req.getStatus().name()));
        }
        Policy policy = policyRepository.findById(req.getPolicyId())
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime cancelDate = OffsetDateTime.now();
        if (cancelDate.isAfter(policy.getPolicyExpirationDate())) {
            cancelDate = policy.getPolicyExpirationDate();
        }
        policy.setStatus(PolicyStatus.cancelled);
        policy.setCancelDate(cancelDate);
        policyRepository.save(policy);

        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId());
        for (ExposureSegment seg : segments) {
            if (!cancelDate.isBefore(seg.getSegmentStart()) && cancelDate.isBefore(seg.getSegmentEnd())) {
                seg.setSegmentEnd(cancelDate);
                long segDays = ChronoUnit.DAYS.between(seg.getSegmentStart(), cancelDate);
                seg.setEarnedExposureYears(Math.max(0, segDays) / 365.25);
                segmentRepository.save(seg);
            } else if (seg.getSegmentStart().isAfter(cancelDate)) {
                seg.setSegmentEnd(seg.getSegmentStart());
                seg.setEarnedExposureYears(0.0);
                segmentRepository.save(seg);
            }
        }

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(cancelDate, policy.getPolicyExpirationDate());
        enqueueEvent("PolicyCancelled", policy.getPolicyId(), Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "cancel_date", cancelDate.toString(),
                "final_premium_vnd", policy.getFinalPremiumVnd(),
                "remaining_days", remainingDays,
                "term_days", termDays));
        return toResponse(policy);
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
        enqueueEvent("EndorsementRejected", req.getPolicyId(), Map.of(
                "customer_id", req.getCustomerId().toString(),
                "endorsement_request_id", req.getEndorsementRequestId().toString(),
                "review_reason", reason != null ? reason : ""));
        return toEndorsementResponse(req);
    }

    /** Scheduled task: void endorsements in APPROVED_PENDING_PAYMENT past their due date. */
    @Scheduled(fixedRate = 3600_000) // every hour
    @Transactional
    public void expireOverdueEndorsements() {
        List<EndorsementRequestEntity> pending = endorsementRequestRepository
                .findByStatusOrderByDueDateAsc(EndorsementStatus.APPROVED_PENDING_PAYMENT);
        OffsetDateTime now = OffsetDateTime.now();
        for (EndorsementRequestEntity req : pending) {
            if (req.getDueDate() != null && req.getDueDate().isBefore(now)) {
                req.setStatus(EndorsementStatus.VOID);
                endorsementRequestRepository.save(req);
                try {
                    billingClient.voidInvoiceByEndorsement(req.getEndorsementRequestId());
                } catch (Exception ignored) {
                }
                enqueueEvent("EndorsementOverdue", req.getPolicyId(), Map.of(
                        "customer_id", req.getCustomerId().toString(),
                        "endorsement_request_id", req.getEndorsementRequestId().toString(),
                        "policy_id", req.getPolicyId().toString(),
                        "invoice_id", req.getInvoiceId() != null ? req.getInvoiceId().toString() : "",
                        "additional_charge_vnd", req.getQuotedPremiumVnd() != null ? req.getQuotedPremiumVnd() : 0,
                        "due_date", req.getDueDate().toString()));
            }
        }
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
        r.setMaterialChange(true);
        r.setQuotedPremiumVnd(req.getQuotedPremiumVnd());
        r.setReviewReason(req.getReviewReason());
        r.setReviewedBy(req.getReviewedBy());
        r.setReviewedAt(req.getReviewedAt());
        r.setCreatedAt(req.getCreatedAt());
        r.setInvoiceId(req.getInvoiceId());
        r.setDueDate(req.getDueDate());
        return r;
    }

    /**
     * Apply an endorsement to a policy: create the next exposure segment, optionally
     * re-rate the remaining term (material change), bump the policy_document version,
     * and emit the EndorsementApplied event with the real premium_old/premium_new.
     */
    private PolicyResponse applyEndorsement(Policy policy, Map<String, Object> change, OffsetDateTime eff,
                                            boolean material) {
        return applyEndorsement(policy, change, eff, material, null);
    }

    /**
     * Applies an endorsement to the policy. When {@code lockedPremium} is non-null (material
     * endorsement approved by admin), the quoted premium is used as-is — no re-rate — so the
     * price the customer pays matches the price recorded on the policy and credit.
     */
    private PolicyResponse applyEndorsement(Policy policy, Map<String, Object> change, OffsetDateTime eff,
                                            boolean material,
                                            Long lockedPremium) {
        UUID policyId = policy.getPolicyId();
        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment prior = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        int nextSeq = prior == null ? 0 : prior.getExposureSegmentSeq() + 1;

        // A1/A6: coverage and deductible always inherited from prior segment, never from request.
        long newCoverage = prior != null ? prior.getCoverageAmountVnd() : 0L;
        long newDeductible = prior != null ? prior.getDeductibleVnd() : 0L;

        long premiumOld = policy.getFinalPremiumVnd();
        long premiumNew = premiumOld;

        // Build the new effective risk profile by merging the change set onto the full
        // base profile carried by the most recent segment.
        Map<String, Object> mergedProfile = prior != null ? readRiskSnapshot(prior) : new LinkedHashMap<>();
        mergedProfile.putAll(change);
        mergedProfile.put("coverage_amount_vnd", newCoverage);
        mergedProfile.put("deductible_vnd", newDeductible);

        if (lockedPremium != null) {
            // Material endorsement: use the admin-approved quoted premium (price lock).
            premiumNew = lockedPremium;
            policy.setFinalPremiumVnd(premiumNew);
            policyRepository.save(policy);
        } else {
            // Re-rate if pricing is available; fail safe by keeping prior premium.
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
        }

        // A6: Close prior segment at eff and recompute earned exposure before creating new segment.
        if (prior != null && prior.getSegmentEnd() != null && prior.getSegmentEnd().isAfter(eff)) {
            prior.setSegmentEnd(eff);
            long priorDays = ChronoUnit.DAYS.between(prior.getSegmentStart(), eff);
            prior.setEarnedExposureYears(Math.max(0, priorDays) / 365.25);
            segmentRepository.save(prior);
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

        long priorCoverage = prior != null ? prior.getCoverageAmountVnd() : 0L;
        long priorDeductible = prior != null ? prior.getDeductibleVnd() : 0L;
        Map<String, Object> structuredChange = new LinkedHashMap<>();
        // Coverage/deductible are always inherited — they never appear in structuredChange.
        // Only premium diff is recorded for the document.
        if (premiumNew != premiumOld) {
            structuredChange.put("premium", Map.of("old", premiumOld, "new", premiumNew));
        }

        OffsetDateTime issuedAt = OffsetDateTime.now();
        String docLine = resolveLineFromProductId(policy.getProductId());
        Map<String, Object> docContent = PolicyDocumentContentBuilder.build(
                newVersion, policy, docLine,
                newCoverage, newDeductible,
                structuredChange.isEmpty() ? null : structuredChange, issuedAt);
        try {
            doc.setContent(objectMapper.writeValueAsString(docContent));
        } catch (Exception e) {
            doc.setContent("{}");
        }
        doc.setCreatedAt(issuedAt);
        documentRepository.save(doc);

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(eff, policy.getPolicyExpirationDate());
        enqueueEvent("EndorsementApplied", policyId, Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "order_id", policy.getOrderId().toString(),
                "effective_date", eff.toString(),
                "premium_old", premiumOld, "premium_new", premiumNew,
                "difference_vnd", premiumNew - premiumOld,
                "remaining_days", remainingDays, "term_days", termDays));
        return toResponse(policy);
    }

    private boolean isMaterialChange(Map<String, Object> change) {
        if (change == null || change.isEmpty()) {
            return false;
        }
        // After locking coverage/deductible, every remaining attribute is a risk attribute.
        return true;
    }

    /**
     * Validate that all change keys are allowed for the policy's product line.
     * Prevents cross-line attribute contamination (e.g. sending smoker for a motor policy).
     */
    private void validateChangeKeys(Map<String, Object> change, String productId) {
        if (change == null || change.isEmpty()) {
            throw new ServiceException(ErrorCode.INVALID_ENDORSEMENT_ATTRIBUTE,
                    "Endorsement change set must not be empty", null);
        }
        String line = resolveLineFromProductId(productId);
        Set<String> allowed = ALLOWED_KEYS_BY_LINE.get(line);
        if (allowed == null) {
            throw new ServiceException(ErrorCode.INVALID_ENDORSEMENT_ATTRIBUTE,
                    "Unknown product line for endorsement", Map.of("product_id", productId));
        }
        for (Map.Entry<String, Object> entry : change.entrySet()) {
            String key = entry.getKey();
            if (BLOCKED_KEYS.contains(key)) {
                throw new ServiceException(ErrorCode.INVALID_ENDORSEMENT_ATTRIBUTE,
                        "Coverage and deductible cannot be changed through endorsement",
                        Map.of("attribute", key));
            }
            if (entry.getValue() == null) {
                throw new ServiceException(ErrorCode.INVALID_ENDORSEMENT_ATTRIBUTE,
                        "Change value for '" + key + "' must not be null",
                        Map.of("attribute", key));
            }
            if (!allowed.contains(key)) {
                throw new ServiceException(ErrorCode.INVALID_ENDORSEMENT_ATTRIBUTE,
                        "Attribute '" + key + "' is not valid for line '" + line + "'",
                        Map.of("attribute", key, "line", line));
            }
        }
    }

    private String resolveLineFromProductId(String productId) {
        if (productId == null) {
            return "";
        }
        String lower = productId.toLowerCase();
        if (lower.startsWith("motor") || lower.startsWith("bike")) return "motorbike";
        if (lower.startsWith("car") || lower.startsWith("auto")) return "car";
        if (lower.startsWith("health") || lower.startsWith("medical")) return "health";
        if (lower.startsWith("home") || lower.startsWith("house") || lower.startsWith("property")) return "home";
        if (lower.startsWith("accident") || lower.startsWith("personal")) return "accident";
        if (lower.startsWith("travel") || lower.startsWith("trip")) return "travel";
        return "";
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
        renewed.setAssetKey(old.getAssetKey());
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
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        UUID customerId = CustomerId.fromSubject(keycloakSubject);
        if (!policy.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null);
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
        resp.setLine(policy.getLine());
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
