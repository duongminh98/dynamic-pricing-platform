package dpp.order.service;

import dpp.common.security.CustomerId;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.dto.*;
import dpp.order.entity.*;
import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.order.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final BillingClient billingClient;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    @Autowired
    public PolicyLifecycleService(PolicyRepository policyRepository, ExposureSegmentRepository segmentRepository,
                                   PolicyDocumentRepository documentRepository,
                                   EndorsementRequestRepository endorsementRequestRepository,
                                   BillingClient billingClient,
                                   OutboxPublisher outboxPublisher) {
        this.policyRepository = policyRepository;
        this.segmentRepository = segmentRepository;
        this.documentRepository = documentRepository;
        this.endorsementRequestRepository = endorsementRequestRepository;
        this.billingClient = billingClient;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    public PolicyLifecycleService(PolicyRepository policyRepository, ExposureSegmentRepository segmentRepository,
                                   PolicyDocumentRepository documentRepository,
                                   EndorsementRequestRepository endorsementRequestRepository,
                                   PricingClient ignoredPricingClient, BillingClient billingClient,
                                   OutboxPublisher outboxPublisher) {
        this(policyRepository, segmentRepository, documentRepository, endorsementRequestRepository,
                billingClient, outboxPublisher);
    }

    /**
     * Customer endorsement entry point.
     *
     * <p>Every endorsement is a Material_Change: it is persisted as a
     * PENDING_REVIEW endorsement request that only an Administrator can
     * approve/reject - the customer can never self-approve.
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
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime eff = resolveEffectiveDate(request, now);
        if (eff.isBefore(now)) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE,
                    "Effective date cannot be in the past", null);
        }
        if (!eff.isAfter(policy.getPolicyEffectiveDate()) || !eff.isBefore(policy.getPolicyExpirationDate())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE);
        }

        Map<String, Object> change = resolveChangeSet(request);
        validateChangeKeys(change, policy.getProductId());

        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment prior = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        // A6: effective date must move strictly forward past the most recent segment, otherwise
        // segment truncation would produce overlapping/inverted exposure windows.
        requireEffectiveAfterLatestSegment(prior, eff);
        long newCoverage = prior != null ? prior.getCoverageAmountVnd() : 0L;
        long newDeductible = prior != null ? prior.getDeductibleVnd() : 0L;
        Map<String, Object> mergedProfile = prior != null ? readRiskSnapshot(prior) : new LinkedHashMap<>();
        mergedProfile.putAll(change);
        mergedProfile.put("coverage_amount_vnd", newCoverage);
        mergedProfile.put("deductible_vnd", newDeductible);

        UUID pricingRequestId = UUID.randomUUID();
        enqueueRepriceRequested(pricingRequestId, "ENDORSEMENT_PREVIEW", policy, mergedProfile, null);

        long currentPremium = policy.getFinalPremiumVnd();
        EndorsementPreviewResponse resp = new EndorsementPreviewResponse();
        resp.setPolicyId(policyId);
        resp.setEffectiveDate(eff);
        resp.setMaterialChange(true);
        resp.setCurrentPremiumVnd(currentPremium);
        resp.setQuotedPremiumVnd(currentPremium);
        resp.setDifferenceVnd(0L);
        resp.setStatus("PRICING_PENDING");
        resp.setPricingRequestId(pricingRequestId);
        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(eff, policy.getPolicyExpirationDate());
        double fraction = termDays > 0 ? remainingDays / (double) termDays : 0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        long proRated = 0L;
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
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime eff = resolveEffectiveDate(request, now);
        if (eff.isBefore(now)) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE,
                    "Effective date cannot be in the past", null);
        }
        if (!eff.isAfter(policy.getPolicyEffectiveDate()) || !eff.isBefore(policy.getPolicyExpirationDate())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE);
        }

        Map<String, Object> change = resolveChangeSet(request);
        validateChangeKeys(change, policy.getProductId());

        // A4: Block concurrent endorsement - at most one in-progress per policy.
        List<EndorsementRequestEntity> existing = endorsementRequestRepository.findByPolicyIdOrderByCreatedAtDesc(policyId);
        for (EndorsementRequestEntity e : existing) {
            if (e.getStatus() == EndorsementStatus.PENDING_REVIEW
                    || e.getStatus() == EndorsementStatus.PRICING_PENDING
                    || e.getStatus() == EndorsementStatus.APPROVED_PENDING_PAYMENT) {
                throw new ServiceException(ErrorCode.ENDORSEMENT_IN_PROGRESS,
                        "An endorsement is already in progress for this policy",
                        Map.of("endorsement_request_id", e.getEndorsementRequestId().toString()));
            }
        }

        // Request asynchronous pricing; the customer can poll the endorsement until it is priced.
        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment prior = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        // A6: effective date must move strictly forward past the most recent segment, otherwise
        // segment truncation would produce overlapping/inverted exposure windows.
        requireEffectiveAfterLatestSegment(prior, eff);
        long newCoverage = prior != null ? prior.getCoverageAmountVnd() : 0L;
        long newDeductible = prior != null ? prior.getDeductibleVnd() : 0L;
        Map<String, Object> mergedProfile = prior != null ? readRiskSnapshot(prior) : new LinkedHashMap<>();
        mergedProfile.putAll(change);
        mergedProfile.put("coverage_amount_vnd", newCoverage);
        mergedProfile.put("deductible_vnd", newDeductible);
        UUID pricingRequestId = UUID.randomUUID();
        EndorsementRequestEntity pending = new EndorsementRequestEntity();
        pending.setEndorsementRequestId(UUID.randomUUID());
        pending.setPolicyId(policyId);
        pending.setCustomerId(policy.getCustomerId());
        pending.setEffectiveDate(eff);
        pending.setStatus(EndorsementStatus.PRICING_PENDING);
        pending.setCreatedAt(OffsetDateTime.now());
        pending.setQuotedPremiumVnd(null);
        pending.setPricingRequestId(pricingRequestId);
        try {
            pending.setChangeSet(objectMapper.writeValueAsString(change));
        } catch (Exception e) {
            pending.setChangeSet("{}");
        }
        endorsementRequestRepository.save(pending);

        long currentPremium = policy.getFinalPremiumVnd();
        long difference = 0L;
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

        enqueueRepriceRequested(pricingRequestId, "ENDORSEMENT_SUBMIT", policy, mergedProfile, pending.getEndorsementRequestId());

        return EndorsementResult.pricingPending(pending.getEndorsementRequestId(), pricingRequestId,
                eff, pending.getCreatedAt());
    }

    // -- Admin review of Material_Change endorsements (R23.9 / design section 4.2) --

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
    public CancelResponse adminCancelPolicy(UUID policyId, OffsetDateTime cancelDate) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null));
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (cancelDate == null) {
            cancelDate = now;
        }
        // E1: Block backdate - cancel_date must be >= now and within [effective, expiration].
        if (cancelDate.isBefore(now) || cancelDate.isAfter(policy.getPolicyExpirationDate())
                || cancelDate.isBefore(policy.getPolicyEffectiveDate())) {
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

        cancelInFlightEndorsements(policy);

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(cancelDate, policy.getPolicyExpirationDate());
        long refundableCreditVnd = 0L;
        enqueueEvent("PolicyCancelled", policyId, Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "product_id", policy.getProductId(),
                "line", policy.getLine() != null ? policy.getLine() : resolveLineFromProductId(policy.getProductId()),
                "status", policy.getStatus().name(),
                "cancel_date", cancelDate.toString(),
                "final_premium_vnd", policy.getFinalPremiumVnd(),
                "remaining_days", remainingDays,
                "term_days", termDays,
                "refundable_credit_vnd", refundableCreditVnd));

        CancelResponse resp = new CancelResponse();
        resp.setPolicyId(policyId);
        resp.setStatus(PolicyStatus.cancelled);
        resp.setCancelDate(cancelDate);
        resp.setRemainingDays(remainingDays);
        resp.setTermDays(termDays);
        resp.setRefundableCreditVnd(refundableCreditVnd);
        return resp;
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
        if (req.getStatus() == EndorsementStatus.APPROVED_PENDING_PAYMENT) {
            requestEndorsementInvoiceVoid(policy, req);
            invoiceVoided = true;
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
        EndorsementRequestEntity req = findPricedEndorsement(endorsementRequestId);
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

            long netDue = additionalCharge;

            if (netDue >= MIN_SETTLE_AMOUNT) {
                // Invoice creation is now event-driven: billing consumes EndorsementPendingPayment
                // and creates the invoice asynchronously with the GROSS additional charge.
                // Billing applies credits for real via applyCreditsToInvoice.
                OffsetDateTime dueDate = OffsetDateTime.now().plusDays(ENDORSEMENT_PAYMENT_DUE_DAYS);
                req.setStatus(EndorsementStatus.APPROVED_PENDING_PAYMENT);
                req.setReviewedBy(reviewer);
                req.setReviewedAt(OffsetDateTime.now());
                req.setInvoiceId(null);
                req.setDueDate(dueDate);
                endorsementRequestRepository.save(req);

                enqueueEvent("EndorsementPendingPayment", policy.getPolicyId(), Map.of(
                        "customer_id", policy.getCustomerId().toString(),
                        "endorsement_request_id", endorsementRequestId.toString(),
                        "invoice_id", "",
                        "additional_charge_vnd", additionalCharge,
                        "due_date", dueDate.toString(),
                        "order_id", policy.getOrderId().toString()));
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

    /** Called when an adjustment invoice is paid - applies the held endorsement. */
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

    /** Called when an adjustment invoice is voided (e.g. an admin voids it directly from the
     *  billing tab). Without this, the endorsement stays in APPROVED_PENDING_PAYMENT forever with
     *  no payable invoice — approve/apply can never fire and it sticks in the review queue. Void
     *  it (same terminal state the overdue job uses); the change was never applied so the policy
     *  is untouched. Admin can revive via extendDueDate, which accepts VOID. */
    @Transactional
    public void voidEndorsementForVoidedInvoice(UUID endorsementRequestId, UUID voidedInvoiceId) {
        EndorsementRequestEntity req = endorsementRequestRepository.findById(endorsementRequestId).orElse(null);
        if (req == null || req.getStatus() != EndorsementStatus.APPROVED_PENDING_PAYMENT) {
            return;
        }
        // Act ONLY when the voided invoice is the endorsement's current live invoice. extendDueDate
        // and the cancel cascade also void the prior invoice then re-open a new one (invoiceId briefly
        // null, then the new id); a stale InvoiceVoided for the old invoice must never void the
        // freshly re-opened endorsement. A legitimate manual void always carries the matching id.
        if (voidedInvoiceId == null || !voidedInvoiceId.equals(req.getInvoiceId())) {
            return;
        }
        req.setStatus(EndorsementStatus.VOID);
        endorsementRequestRepository.save(req);
        enqueueEvent("EndorsementOverdue", req.getPolicyId(), Map.of(
                "customer_id", req.getCustomerId().toString(),
                "endorsement_request_id", req.getEndorsementRequestId().toString(),
                "policy_id", req.getPolicyId().toString(),
                "invoice_id", req.getInvoiceId() != null ? req.getInvoiceId().toString() : "",
                "additional_charge_vnd", req.getQuotedPremiumVnd() != null ? req.getQuotedPremiumVnd() : 0,
                "due_date", req.getDueDate() != null ? req.getDueDate().toString() : ""));
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

        // Void old invoice asynchronously if it exists (unpaid invoices only; paid ones are left alone).
        if (req.getInvoiceId() != null) {
            requestEndorsementInvoiceVoid(policy, req);
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

        // Invoice creation is now event-driven: billing consumes EndorsementPendingPayment
        // and creates the invoice asynchronously with the GROSS additional charge.
        req.setStatus(EndorsementStatus.APPROVED_PENDING_PAYMENT);
        req.setInvoiceId(null);
        req.setDueDate(newDueDate);
        endorsementRequestRepository.save(req);

        enqueueEvent("EndorsementPendingPayment", policy.getPolicyId(), Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "endorsement_request_id", endorsementRequestId.toString(),
                "invoice_id", "",
                "additional_charge_vnd", additionalCharge,
                "due_date", newDueDate.toString(),
                "order_id", policy.getOrderId().toString()));

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

        cancelInFlightEndorsements(policy);

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(cancelDate, policy.getPolicyExpirationDate());
        enqueueEvent("PolicyCancelled", policy.getPolicyId(), Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "product_id", policy.getProductId(),
                "line", policy.getLine() != null ? policy.getLine() : resolveLineFromProductId(policy.getProductId()),
                "status", policy.getStatus().name(),
                "cancel_date", cancelDate.toString(),
                "final_premium_vnd", policy.getFinalPremiumVnd(),
                "remaining_days", remainingDays,
                "term_days", termDays,
                "refundable_credit_vnd", 0L));
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
                Policy policy = policyRepository.findById(req.getPolicyId()).orElse(null);
                if (policy != null) {
                    requestEndorsementInvoiceVoid(policy, req);
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
        if (req.getStatus() != EndorsementStatus.PENDING_REVIEW) {
            throw new ServiceException(ErrorCode.ORDER_NOT_APPROVED,
                    "Endorsement request is not pending review",
                    Map.of("status", req.getStatus().name()));
        }
        return req;
    }

    private EndorsementRequestEntity findPricedEndorsement(UUID endorsementRequestId) {
        return findPendingEndorsement(endorsementRequestId);
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
        r.setCurrentPremiumVnd(req.getCurrentPremiumVnd());
        r.setQuotedPremiumVnd(req.getQuotedPremiumVnd());
        if (req.getCurrentPremiumVnd() != null && req.getQuotedPremiumVnd() != null) {
            r.setDifferenceVnd(req.getQuotedPremiumVnd() - req.getCurrentPremiumVnd());
        }
        r.setReviewReason(req.getReviewReason());
        r.setReviewedBy(req.getReviewedBy());
        r.setReviewedAt(req.getReviewedAt());
        r.setCreatedAt(req.getCreatedAt());
        r.setInvoiceId(req.getInvoiceId());
        r.setDueDate(req.getDueDate());
        r.setPricingRequestId(req.getPricingRequestId());
        r.setPricingFailedReason(req.getPricingFailedReason());
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
     * endorsement approved by admin), the quoted premium is used as-is - no re-rate - so the
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
            premiumNew = premiumOld;
        }

        // A6: Close prior segment at eff and recompute earned exposure before creating new segment.
        // Guard against overlap/inversion: eff must fall strictly inside the prior open segment.
        // (endorse/preview already reject this, but applyEndorsement is also reachable from the
        // paid-endorsement and waived-endorsement paths, so re-check here as a hard invariant.)
        requireEffectiveAfterLatestSegment(prior, eff);
        if (prior != null && prior.getSegmentEnd() != null && prior.getSegmentEnd().isAfter(eff)) {
            prior.setSegmentEnd(eff);
            long priorDays = ChronoUnit.DAYS.between(prior.getSegmentStart(), eff);
            prior.setEarnedExposureYears(Math.max(0, priorDays) / 365.25);
            // Flush the truncation before inserting the successor segment. Hibernate orders
            // INSERT before UPDATE by default, so without this the new [eff, expiration) segment
            // hits the DB while the prior still ends at expiration, tripping the
            // exposure_segment_no_overlap exclusion constraint (23P01).
            segmentRepository.saveAndFlush(prior);
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
        // Coverage/deductible are always inherited - they never appear in structuredChange.
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
        Map<String, Object> appliedPayload = new LinkedHashMap<>();
        appliedPayload.put("customer_id", policy.getCustomerId().toString());
        appliedPayload.put("order_id", policy.getOrderId().toString());
        appliedPayload.put("product_id", policy.getProductId());
        appliedPayload.put("line", policy.getLine());
        appliedPayload.put("status", policy.getStatus().name());
        appliedPayload.put("effective_date", eff.toString());
        appliedPayload.put("exposure_id", seg.getSegmentId().toString());
        appliedPayload.put("exposure_segment_seq", seg.getExposureSegmentSeq());
        appliedPayload.put("segment_start", seg.getSegmentStart().toString());
        appliedPayload.put("segment_end", seg.getSegmentEnd().toString());
        appliedPayload.put("earned_exposure_years", seg.getEarnedExposureYears());
        appliedPayload.put("coverage_amount_vnd", seg.getCoverageAmountVnd());
        appliedPayload.put("deductible_vnd", seg.getDeductibleVnd());
        appliedPayload.put("final_premium_vnd", premiumNew);
        appliedPayload.put("premium_old", premiumOld);
        appliedPayload.put("premium_new", premiumNew);
        appliedPayload.put("difference_vnd", premiumNew - premiumOld);
        appliedPayload.put("remaining_days", remainingDays);
        appliedPayload.put("term_days", termDays);
        appliedPayload.put("risk_snapshot", seg.getRiskSnapshot());
        enqueueEvent("EndorsementApplied", policyId, appliedPayload);
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

    /**
     * Enforce that an endorsement effective date advances strictly past the most recent
     * exposure segment's start. Without this, a future-dated endorsement followed by an
     * earlier-dated one truncates the wrong segment and leaves two segments covering the
     * same window - which makes claim coverage selection ambiguous (a claim in the overlap
     * would resolve to the stale segment). Segments must stay contiguous and non-overlapping.
     */
    private void requireEffectiveAfterLatestSegment(ExposureSegment prior, OffsetDateTime eff) {
        if (prior != null && prior.getSegmentStart() != null && !eff.isAfter(prior.getSegmentStart())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE,
                    "Effective date must be after the current coverage segment start date",
                    Map.of("segment_start", prior.getSegmentStart().toString(),
                            "effective_date", eff.toString()));
        }
    }

    private OffsetDateTime resolveEffectiveDate(EndorsementRequest request, OffsetDateTime now) {        return request != null && request.getEffectiveDate() != null ? request.getEffectiveDate() : now;
    }

    private Map<String, Object> resolveChangeSet(EndorsementRequest request) {
        if (request == null || request.getChange() == null) {
            return new LinkedHashMap<>();
        }
        return request.getChange();
    }

    @Transactional(readOnly = true)
    public RenewalPreviewResponse previewRenewal(UUID policyId, String keycloakSubject) {
        Policy old = findOwnedPolicy(policyId, keycloakSubject);
        if (old.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime newEff = old.getPolicyExpirationDate().isBefore(now) ? now : old.getPolicyExpirationDate();
        OffsetDateTime newExp = newEff.plus(365, ChronoUnit.DAYS);

        List<ExposureSegment> oldSegments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment oldLatest = oldSegments.isEmpty() ? null : oldSegments.get(oldSegments.size() - 1);
        Map<String, Object> profile = oldLatest != null ? readRiskSnapshot(oldLatest) : new LinkedHashMap<>();
        profile.put("is_renewal", true);
        profile.put("renewal_number", old.getRenewalNumber() + 1);
        profile.put("years_since_first_policy", old.getYearsSinceFirstPolicy() + 1);
        profile.put("policy_count_prior", old.getPolicyCountPrior() + 1);

        UUID pricingRequestId = UUID.randomUUID();
        enqueueRepriceRequested(pricingRequestId, "RENEWAL_PREVIEW", old, profile, null);

        long renewedPremium = old.getFinalPremiumVnd();

        long creditApplied = 0;
        long netDue = renewedPremium;

        long coverage = oldLatest != null ? oldLatest.getCoverageAmountVnd() : 0L;
        long deductible = oldLatest != null ? oldLatest.getDeductibleVnd() : 0L;

        RenewalPreviewResponse resp = new RenewalPreviewResponse();
        resp.setPolicyId(policyId);
        resp.setRenewalNumber(old.getRenewalNumber() + 1);
        resp.setNewEffectiveDate(newEff);
        resp.setNewExpirationDate(newExp);
        resp.setCurrentPremiumVnd(old.getFinalPremiumVnd());
        resp.setRenewedPremiumVnd(renewedPremium);
        resp.setCreditAppliedVnd(creditApplied);
        resp.setNetDueVnd(netDue);
        resp.setCoverageAmountVnd(coverage);
        resp.setDeductibleVnd(deductible);
        resp.setPaymentRequired(netDue >= MIN_SETTLE_AMOUNT);
        resp.setStatus("PRICING_PENDING");
        resp.setPricingRequestId(pricingRequestId);
        return resp;
    }

    @Transactional
    public RenewalResponse renew(UUID policyId, String keycloakSubject) {
        Policy old = findOwnedPolicy(policyId, keycloakSubject);
        // B1: Status guard - only active policies can be renewed.
        if (old.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        // B2: Duplicate guard - block if a renewal already exists for the next term.
        int nextRenewalNumber = old.getRenewalNumber() + 1;
        List<Policy> existingRenewals = policyRepository.findByOrderIdAndStatusIn(
                old.getOrderId(),
                List.of(PolicyStatus.active, PolicyStatus.pending_payment, PolicyStatus.pricing_pending, PolicyStatus.pricing_failed));
        for (Policy p : existingRenewals) {
            if (p.getRenewalNumber() == nextRenewalNumber && !p.getPolicyId().equals(old.getPolicyId())) {
                throw new ServiceException(ErrorCode.RENEWAL_IN_PROGRESS,
                        "A renewal already exists for the next term",
                        Map.of("existing_policy_id", p.getPolicyId().toString()));
            }
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime newEff = old.getPolicyExpirationDate().isBefore(now) ? now : old.getPolicyExpirationDate();
        OffsetDateTime newExp = newEff.plus(365, ChronoUnit.DAYS);

        // B3: Request asynchronous reprice with full risk profile + renewal context.
        List<ExposureSegment> oldSegments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        ExposureSegment oldLatest = oldSegments.isEmpty() ? null : oldSegments.get(oldSegments.size() - 1);
        Map<String, Object> profile = oldLatest != null ? readRiskSnapshot(oldLatest) : new LinkedHashMap<>();
        profile.put("is_renewal", true);
        profile.put("renewal_number", old.getRenewalNumber() + 1);
        profile.put("years_since_first_policy", old.getYearsSinceFirstPolicy() + 1);
        profile.put("policy_count_prior", old.getPolicyCountPrior() + 1);

        UUID pricingRequestId = UUID.randomUUID();
        enqueueRepriceRequested(pricingRequestId, "RENEWAL_PREVIEW", old, profile, null);

        long renewedPremium = old.getFinalPremiumVnd();

        // Billing applies customer-scoped credit asynchronously when it creates the invoice.
        long creditApplied = 0;
        long netDue = renewedPremium;

        // B5: Gate-by-payment.
        boolean paymentRequired = netDue >= MIN_SETTLE_AMOUNT;

        Policy renewed = new Policy();
        renewed.setPolicyId(UUID.randomUUID());
        renewed.setOrderId(old.getOrderId());
        renewed.setCustomerId(old.getCustomerId());
        renewed.setProductId(old.getProductId());
        renewed.setLine(old.getLine());
        renewed.setStatus(PolicyStatus.pricing_pending);
        renewed.setPolicyEffectiveDate(newEff);
        renewed.setPolicyExpirationDate(newExp);
        renewed.setRenewalNumber(nextRenewalNumber);
        renewed.setRenewal(true);
        renewed.setYearsSinceFirstPolicy(old.getYearsSinceFirstPolicy() + 1);
        renewed.setPolicyCountPrior(old.getPolicyCountPrior() + 1);
        renewed.setFinalPremiumVnd(renewedPremium);
        renewed.setPricingRequestId(pricingRequestId);
        renewed.setAssetKey(old.getAssetKey());
        renewed.setCreatedAt(now);
        policyRepository.save(renewed);

        // B6: Stamp segment 0 with inherited coverage/deductible.
        long newCoverage = oldLatest != null ? oldLatest.getCoverageAmountVnd() : 0L;
        long newDeductible = oldLatest != null ? oldLatest.getDeductibleVnd() : 0L;
        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(renewed.getPolicyId());
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(newEff);
        seg.setSegmentEnd(newExp);
        long days = ChronoUnit.DAYS.between(newEff, newExp);
        seg.setEarnedExposureYears(days / 365.25);
        seg.setCoverageAmountVnd(newCoverage);
        seg.setDeductibleVnd(newDeductible);
        try {
            seg.setRiskSnapshot(objectMapper.writeValueAsString(profile));
        } catch (Exception e) {
            seg.setRiskSnapshot("{}");
        }
        segmentRepository.save(seg);

        RenewalResponse resp = new RenewalResponse();
        resp.setPolicyId(renewed.getPolicyId());
        resp.setPreviousPolicyId(policyId);
        resp.setRenewalNumber(nextRenewalNumber);
        resp.setRenewedPremiumVnd(renewedPremium);
        resp.setCreditAppliedVnd(creditApplied);
        resp.setNetDueVnd(netDue);
        resp.setPaymentRequired(paymentRequired);
        resp.setNewEffectiveDate(newEff);
        resp.setNewExpirationDate(newExp);

        resp.setStatus(PolicyStatus.pricing_pending);
        resp.setPricingRequestId(pricingRequestId);
        enqueueRepriceRequested(pricingRequestId, "RENEWAL_SUBMIT", old, profile, renewed.getPolicyId());
        return resp;
    }

    @Transactional
    public void activateRenewedPolicy(UUID policyId) {
        Policy policy = policyRepository.findById(policyId).orElse(null);
        if (policy == null || policy.getStatus() != PolicyStatus.pending_payment) {
            return;
        }
        policy.setStatus(PolicyStatus.active);
        policyRepository.save(policy);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customer_id", policy.getCustomerId().toString());
        payload.put("renewal_number", policy.getRenewalNumber());
        payload.put("renewed_premium_vnd", policy.getFinalPremiumVnd());
        payload.put("new_effective_date", policy.getPolicyEffectiveDate().toString());
        payload.put("new_expiration_date", policy.getPolicyExpirationDate().toString());
        enqueueEvent("PolicyRenewed", policyId, payload);
    }

    @Transactional
    public CancelResponse cancel(UUID policyId, CancelRequest request, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        // E1: Block backdate - cancel_date must be >= now and within [effective, expiration].
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cancelDate = request != null && request.getCancelDate() != null ? request.getCancelDate() : now;
        if (cancelDate.isBefore(now) || cancelDate.isAfter(policy.getPolicyExpirationDate())
                || cancelDate.isBefore(policy.getPolicyEffectiveDate())) {
            throw new ServiceException(ErrorCode.CANCEL_DATE_OUT_OF_RANGE);
        }
        policy.setStatus(PolicyStatus.cancelled);
        policy.setCancelDate(cancelDate);
        policyRepository.save(policy);

        // E2: Cut the exposure segment covering cancel_date and recompute earned exposure.
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

        cancelInFlightEndorsements(policy);

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(cancelDate, policy.getPolicyExpirationDate());

        long refundableCreditVnd = 0L;
        enqueueEvent("PolicyCancelled", policyId, Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "product_id", policy.getProductId(),
                "line", policy.getLine() != null ? policy.getLine() : resolveLineFromProductId(policy.getProductId()),
                "status", policy.getStatus().name(),
                "cancel_date", cancelDate.toString(),
                "final_premium_vnd", policy.getFinalPremiumVnd(),
                "remaining_days", remainingDays,
                "term_days", termDays,
                "refundable_credit_vnd", refundableCreditVnd));

        CancelResponse resp = new CancelResponse();
        resp.setPolicyId(policyId);
        resp.setStatus(PolicyStatus.cancelled);
        resp.setCancelDate(cancelDate);
        resp.setRemainingDays(remainingDays);
        resp.setTermDays(termDays);
        resp.setRefundableCreditVnd(refundableCreditVnd);
        return resp;
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


    @Transactional
    public void handleRepriceCompleted(String pricingRequestIdText, String workflow, Long finalPremiumVnd, String failureReason) {
        UUID pricingRequestId = UUID.fromString(pricingRequestIdText);
        if ("ENDORSEMENT_SUBMIT".equals(workflow)) {
            handleEndorsementRepriced(pricingRequestId, finalPremiumVnd, failureReason);
        } else if ("RENEWAL_SUBMIT".equals(workflow)) {
            handleRenewalRepriced(pricingRequestId, finalPremiumVnd, failureReason);
        }
    }

    private void handleEndorsementRepriced(UUID pricingRequestId, Long finalPremiumVnd, String failureReason) {
        EndorsementRequestEntity req = endorsementRequestRepository.findByPricingRequestId(pricingRequestId).orElse(null);
        if (req == null || req.getStatus() != EndorsementStatus.PRICING_PENDING) {
            return;
        }
        if (finalPremiumVnd == null) {
            req.setStatus(EndorsementStatus.PRICING_FAILED);
            req.setPricingFailedReason(failureReason);
            endorsementRequestRepository.save(req);
            return;
        }
        Policy policy = policyRepository.findById(req.getPolicyId()).orElse(null);
        long currentPremium = policy != null ? policy.getFinalPremiumVnd() : 0L;
        long termDays = policy != null ? ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate()) : 0L;
        long remainingDays = policy != null ? ChronoUnit.DAYS.between(req.getEffectiveDate(), policy.getPolicyExpirationDate()) : 0L;
        double fraction = termDays > 0 ? remainingDays / (double) termDays : 0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        req.setCurrentPremiumVnd(currentPremium);
        req.setQuotedPremiumVnd(finalPremiumVnd);
        req.setStatus(EndorsementStatus.PENDING_REVIEW);
        req.setPricingFailedReason(null);
        endorsementRequestRepository.save(req);
        enqueueEvent("EndorsementPriced", req.getPolicyId(), Map.of(
                "customer_id", req.getCustomerId().toString(),
                "policy_id", req.getPolicyId().toString(),
                "endorsement_request_id", req.getEndorsementRequestId().toString(),
                "pricing_request_id", pricingRequestId.toString(),
                "quoted_premium_vnd", finalPremiumVnd,
                "difference_vnd", finalPremiumVnd - currentPremium,
                "pro_rated_charge_vnd", Math.round((finalPremiumVnd - currentPremium) * fraction)));
    }

    private void handleRenewalRepriced(UUID pricingRequestId, Long finalPremiumVnd, String failureReason) {
        Policy renewed = policyRepository.findByPricingRequestId(pricingRequestId).orElse(null);
        if (renewed == null || renewed.getStatus() != PolicyStatus.pricing_pending) {
            return;
        }
        if (finalPremiumVnd == null) {
            renewed.setStatus(PolicyStatus.pricing_failed);
            renewed.setPricingFailedReason(failureReason);
            policyRepository.save(renewed);
            return;
        }
        renewed.setFinalPremiumVnd(finalPremiumVnd);
        renewed.setPricingFailedReason(null);
        long netDue = finalPremiumVnd;
        boolean paymentRequired = netDue >= MIN_SETTLE_AMOUNT;
        renewed.setStatus(paymentRequired ? PolicyStatus.pending_payment : PolicyStatus.active);
        policyRepository.save(renewed);
        ExposureSegment seg = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(renewed.getPolicyId())
                .stream().findFirst().orElse(null);
        Map<String, Object> renewalPayload = new LinkedHashMap<>();
        renewalPayload.put("customer_id", renewed.getCustomerId().toString());
        renewalPayload.put("order_id", renewed.getOrderId().toString());
        renewalPayload.put("product_id", renewed.getProductId());
        renewalPayload.put("line", renewed.getLine());
        renewalPayload.put("status", renewed.getStatus().name());
        renewalPayload.put("previous_policy_id", "");
        renewalPayload.put("renewal_number", renewed.getRenewalNumber());
        renewalPayload.put("renewed_premium_vnd", finalPremiumVnd);
        renewalPayload.put("credit_applied_vnd", 0);
        renewalPayload.put("net_due_vnd", netDue);
        renewalPayload.put("new_effective_date", renewed.getPolicyEffectiveDate().toString());
        renewalPayload.put("new_expiration_date", renewed.getPolicyExpirationDate().toString());
        if (seg != null) {
            renewalPayload.put("exposure_id", seg.getSegmentId().toString());
            renewalPayload.put("exposure_segment_seq", seg.getExposureSegmentSeq());
            renewalPayload.put("segment_start", seg.getSegmentStart().toString());
            renewalPayload.put("segment_end", seg.getSegmentEnd().toString());
            renewalPayload.put("earned_exposure_years", seg.getEarnedExposureYears());
            renewalPayload.put("coverage_amount_vnd", seg.getCoverageAmountVnd());
            renewalPayload.put("deductible_vnd", seg.getDeductibleVnd());
            renewalPayload.put("risk_snapshot", seg.getRiskSnapshot());
        }
        renewalPayload.put("final_premium_vnd", finalPremiumVnd);
        renewalPayload.put("payment_required", paymentRequired);
        enqueueEvent("PolicyRenewed", renewed.getPolicyId(), renewalPayload);
    }

    private void enqueueRepriceRequested(UUID pricingRequestId, String workflow, Policy policy, Map<String, Object> profile, UUID aggregateId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pricing_request_id", pricingRequestId.toString());
        payload.put("workflow", workflow);
        payload.put("customer_id", policy.getCustomerId().toString());
        payload.put("policy_id", policy.getPolicyId().toString());
        payload.put("aggregate_id", aggregateId != null ? aggregateId.toString() : "");
        payload.put("product_id", policy.getProductId());
        payload.put("line", policy.getLine());
        payload.put("profile", profile);
        enqueueEvent("RepriceRequested", aggregateId != null ? aggregateId : policy.getPolicyId(), payload);
    }

    private void requestEndorsementInvoiceVoid(Policy policy, EndorsementRequestEntity req) {
        enqueueEvent("EndorsementInvoiceVoidRequested", policy.getPolicyId(), Map.of(
                "customer_id", policy.getCustomerId().toString(),
                "order_id", policy.getOrderId().toString(),
                "endorsement_request_id", req.getEndorsementRequestId().toString(),
                "invoice_id", req.getInvoiceId() != null ? req.getInvoiceId().toString() : ""));
    }

    /** When a policy is cancelled, any in-flight endorsement can never be applied to it — a coverage
     *  change on a dead policy is meaningless. Cancel each one and void its unpaid adjustment invoice,
     *  otherwise the endorsement sticks in PENDING_REVIEW/APPROVED_PENDING_PAYMENT and admin approve
     *  hits the policy-active guard (POLICY_NOT_MODIFIABLE) forever. */
    private void cancelInFlightEndorsements(Policy policy) {
        List<EndorsementRequestEntity> existing =
                endorsementRequestRepository.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId());
        OffsetDateTime cancelledAt = OffsetDateTime.now();
        for (EndorsementRequestEntity req : existing) {
            EndorsementStatus s = req.getStatus();
            if (s != EndorsementStatus.PENDING_REVIEW
                    && s != EndorsementStatus.PRICING_PENDING
                    && s != EndorsementStatus.APPROVED_PENDING_PAYMENT) {
                continue;
            }
            if (s == EndorsementStatus.APPROVED_PENDING_PAYMENT && req.getInvoiceId() != null) {
                requestEndorsementInvoiceVoid(policy, req);
            }
            req.setStatus(EndorsementStatus.CANCELLED);
            req.setReviewReason("Policy cancelled");
            req.setReviewedAt(cancelledAt);
            endorsementRequestRepository.save(req);
            enqueueEvent("EndorsementCancelled", policy.getPolicyId(), Map.of(
                    "customer_id", policy.getCustomerId().toString(),
                    "policy_id", policy.getPolicyId().toString(),
                    "endorsement_request_id", req.getEndorsementRequestId().toString(),
                    "policy_changed", false));
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
