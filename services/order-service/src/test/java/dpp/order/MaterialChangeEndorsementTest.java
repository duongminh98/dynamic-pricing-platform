package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.order.dto.EndorsementRequest;
import dpp.order.dto.EndorsementRequestResponse;
import dpp.order.dto.EndorsementResult;
import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.EndorsementStatus;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Material_Change endorsement behaviour (R23.2, R23.7, R23.8, R23.9, BR-21).
 *
 * <p>Security gate (Task 20.9 fix): a Customer can NEVER self-approve a material
 * change. A customer material change goes to PENDING_REVIEW (not applied); only an
 * Administrator can approve (apply + re-rate) or reject (no-op, terminal) it.
 */
@Tag("Feature: dynamic-pricing-platform, Property 10")
class MaterialChangeEndorsementTest {

    private static final String SUBJECT = "owner-subject";
    private static final String ADMIN = "admin-subject";

    private Policy activePolicy() {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(UUID.nameUUIDFromBytes(SUBJECT.getBytes()));
        p.setProductId("MOTOR_BASIC");
        p.setStatus(PolicyStatus.active);
        OffsetDateTime eff = OffsetDateTime.now().plusDays(1);
        p.setPolicyEffectiveDate(eff);
        p.setPolicyExpirationDate(eff.plus(365, ChronoUnit.DAYS));
        p.setFinalPremiumVnd(1_000_000L);
        return p;
    }

    private PolicyLifecycleService svc(Policy policy, PricingClient pricing, ExposureSegmentRepository segRepo,
                                       PolicyDocumentRepository docRepo, PolicyRepository repo,
                                       EndorsementRequestRepository endRepo) {
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId())).thenReturn(List.of());
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId())).thenReturn(List.of());
        BillingClient billing = mock(BillingClient.class);
        return new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, billing, mock(OutboxPublisher.class));
    }

    private EndorsementRequest materialRequest(Policy policy) {
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("vehicle_value_vnd", 500_000_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        return req;
    }

    @Test
    void customerMaterialChangeGoesToPendingReviewWithProvisionalQuote() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_200_000L));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementResult result = s.endorse(policy.getPolicyId(), materialRequest(policy), SUBJECT);

        assertEquals("PRICING_PENDING", result.getStatus());
        assertNotNull(result.getEndorsementRequestId());
        assertNull(result.getQuotedPremiumVnd(),
                "customer receives premium after RepriceCompleted event");
        // Re-rate is called for the provisional quote, but the change is NOT applied.
        verify(pricing, never()).rerate(eq("MOTOR_BASIC"), anyMap());
        verify(segRepo, never()).save(any(ExposureSegment.class));
        assertEquals(1_000_000L, policy.getFinalPremiumVnd(), "premium must not change before admin approval");
        // A PENDING_REVIEW request must be persisted with the quoted premium.
        ArgumentCaptor<EndorsementRequestEntity> captor = ArgumentCaptor.forClass(EndorsementRequestEntity.class);
        verify(endRepo, times(1)).save(captor.capture());
        assertEquals(EndorsementStatus.PRICING_PENDING, captor.getValue().getStatus());
        assertEquals(policy.getPolicyId(), captor.getValue().getPolicyId());
        assertNull(captor.getValue().getQuotedPremiumVnd(),
                "pending entity stores premium after RepriceCompleted event");
    }

    @Test
    void endorsementWithoutEffectiveDateDefaultsToNow() {
        Policy policy = activePolicy();
        policy.setPolicyEffectiveDate(OffsetDateTime.now().minusDays(1));
        policy.setPolicyExpirationDate(OffsetDateTime.now().plusDays(364));

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_200_000L));
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, mock(ExposureSegmentRepository.class),
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementRequest req = new EndorsementRequest();
        req.setChange(Map.of("vehicle_value_vnd", 500_000_000L));

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);
        OffsetDateTime after = OffsetDateTime.now().plusSeconds(1);

        assertFalse(result.getEffectiveDate().isBefore(before));
        assertFalse(result.getEffectiveDate().isAfter(after));
        assertEquals("PRICING_PENDING", result.getStatus());
    }

    @Test
    void adminApproveAppliesEndorsementAndReRates() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_500_000L));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementRequestEntity pending = pendingEntity(policy);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        EndorsementRequestResponse resp = s.approveEndorsement(pending.getEndorsementRequestId(), ADMIN);

        assertEquals(EndorsementStatus.APPROVED_PENDING_PAYMENT, resp.getStatus());
        assertEquals(ADMIN, resp.getReviewedBy());
        assertEquals(1_000_000L, policy.getFinalPremiumVnd(), "premium must stay locked until payment");
        verify(pricing, never()).rerate(eq("MOTOR_BASIC"), anyMap());
        verify(segRepo, never()).save(any(ExposureSegment.class));
        assertEquals(EndorsementStatus.APPROVED_PENDING_PAYMENT, pending.getStatus());
    }

    @Test
    void adminRejectDoesNothingToPolicyAndBlocksSubsequentApprove() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementRequestEntity pending = pendingEntity(policy);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        EndorsementRequestResponse resp = s.rejectEndorsement(pending.getEndorsementRequestId(), "fraud risk", ADMIN);

        assertEquals(EndorsementStatus.REJECTED, resp.getStatus());
        assertEquals("fraud risk", resp.getReviewReason());
        verify(pricing, never()).rerate(anyString(), anyMap());
        verify(segRepo, never()).save(any(ExposureSegment.class));
        assertEquals(1_000_000L, policy.getFinalPremiumVnd(), "rejected endorsement must not change premium");

        // Terminal state: a subsequent admin action on the same request is blocked.
        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.approveEndorsement(pending.getEndorsementRequestId(), ADMIN));
        assertEquals(ErrorCode.ORDER_NOT_APPROVED, ex.getErrorCode());
    }

    @Test
    void emptyChangeSetIsRejected() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementRequest req = new EndorsementRequest();
        req.setChange(new HashMap<>()); // empty change
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.INVALID_ENDORSEMENT_ATTRIBUTE, ex.getErrorCode());
    }

    @Test
    void adminApproveReRatesWithFullMergedProfile() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_500_000L));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        PolicyRepository repo = mock(PolicyRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Prior segment carries the full base profile stamped at issuance.
        ExposureSegment base = new ExposureSegment();
        base.setSegmentId(UUID.randomUUID());
        base.setPolicyId(policy.getPolicyId());
        base.setExposureSegmentSeq(0);
        base.setSegmentStart(policy.getPolicyEffectiveDate());
        base.setSegmentEnd(policy.getPolicyExpirationDate());
        base.setCoverageAmountVnd(300_000_000L);
        base.setDeductibleVnd(500_000L);
        base.setRiskSnapshot("{\"age\":40,\"gender\":\"Male\",\"province\":\"Ha Noi\","
                + "\"vehicle_value_vnd\":300000000}");
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(base));

        PolicyLifecycleService s = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo,
                pricing, billingMock(), mock(OutboxPublisher.class));
        EndorsementRequestEntity pending = pendingEntity(policy);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        s.approveEndorsement(pending.getEndorsementRequestId(), ADMIN);

        // With price lock, rerate is NOT called at approve time — the locked quoted premium is used.
        verify(pricing, never()).rerate(eq("MOTOR_BASIC"), anyMap());
        assertEquals(EndorsementStatus.APPROVED_PENDING_PAYMENT, pending.getStatus(),
                "premium increase approval waits for billing invoice/payment event");
        verify(segRepo, never()).save(any(ExposureSegment.class));
    }

    @Test
    void healthRiskAttributeChangeIsMaterialAndGoesToReview() {
        Policy policy = activePolicy();
        policy.setProductId("HEALTH_BASIC");
        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        // A health attribute (not in the legacy motor key list) must now be material.
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("smoker", true);
        change.put("bmi", 31.0);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("PRICING_PENDING", result.getStatus(), "any risk attribute change must be material");
        // Re-rate is called for the provisional quote, but the change is not applied.
        verify(pricing, never()).rerate(eq("HEALTH_BASIC"), anyMap());
        verify(endRepo, times(1)).save(any(EndorsementRequestEntity.class));
    }

    @Test
    void coverageDeductibleInChangeSetIsRejected() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("coverage_amount_vnd", 800_000_000L);
        change.put("deductible_vnd", 500_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.INVALID_ENDORSEMENT_ATTRIBUTE, ex.getErrorCode());
    }

    // ── Real-world scenario: health policy endorsement ──

    /**
     * Scenario 1 — Material change on a health policy.
     *
     * <p>Customer bought a health policy (non-smoker, age 35, BMI 22).
     * On day 104 of 365 they declare smoker=true and BMI=28.
     * Expected: material → PENDING_REVIEW → admin approves → re-rate with
     * full merged profile → premium changes → EndorsementApplied event
     * carries premium_old, premium_new, remaining_days=260, term_days=365.
     */
    @Test
    void healthEndorsementSmokerBmiChange_fullFlow() throws Exception {
        // --- Setup: health policy with base profile segment ---
        Policy policy = activePolicy();
        policy.setProductId("HEALTH_BASIC");
        policy.setFinalPremiumVnd(12_000_000L);
        OffsetDateTime eff = policy.getPolicyEffectiveDate();
        OffsetDateTime exp = policy.getPolicyExpirationDate();

        ExposureSegment base = new ExposureSegment();
        base.setSegmentId(UUID.randomUUID());
        base.setPolicyId(policy.getPolicyId());
        base.setExposureSegmentSeq(0);
        base.setSegmentStart(eff);
        base.setSegmentEnd(exp);
        base.setCoverageAmountVnd(500_000_000L);
        base.setDeductibleVnd(5_000_000L);
        base.setRiskSnapshot("{\"age\":35,\"smoker\":false,\"bmi\":22,\"gender\":\"Male\"}");

        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        PolicyRepository repo = mock(PolicyRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        BillingClient billing = mock(BillingClient.class);

        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(base));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Pricing returns 18M for the provisional quote (submit only — approve uses price lock).
        when(pricing.rerate(eq("HEALTH_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 18_000_000L));
        // Billing net-off quote returns full amount due (no credits available).

        PolicyLifecycleService s = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, billing, outbox);

        // --- Step 1: customer submits endorsement (smoker + bmi change) ---
        OffsetDateTime endorseDate = eff.plusDays(104);
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("smoker", true);
        change.put("bmi", 28);
        req.setChange(change);
        req.setEffectiveDate(endorseDate);

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("PRICING_PENDING", result.getStatus(),
                "smoker + bmi change must be material and go to admin review");
        assertNull(result.getQuotedPremiumVnd(),
                "customer receives premium after RepriceCompleted event");
        assertEquals(12_000_000L, policy.getFinalPremiumVnd(),
                "policy premium must not change before admin approval");

        // --- Step 2: admin approves → price lock, invoice created, wait for payment ---

        // Capture the pending entity that was saved during endorse()
        ArgumentCaptor<EndorsementRequestEntity> endCaptor = ArgumentCaptor.forClass(EndorsementRequestEntity.class);
        verify(endRepo).save(endCaptor.capture());
        EndorsementRequestEntity pending = endCaptor.getValue();
        when(endRepo.findByPricingRequestId(pending.getPricingRequestId())).thenReturn(Optional.of(pending));
        s.handleRepriceCompleted(pending.getPricingRequestId().toString(), "ENDORSEMENT_SUBMIT", 18_000_000L, null);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        EndorsementRequestResponse approveResp = s.approveEndorsement(pending.getEndorsementRequestId(), ADMIN);

        assertEquals(EndorsementStatus.APPROVED_PENDING_PAYMENT, approveResp.getStatus(),
                "premium increase endorsement must wait for payment");
        assertEquals(12_000_000L, policy.getFinalPremiumVnd(),
                "premium must not change until payment is received");

        // Simulate payment → endorsement applied with locked premium
        s.applyPendingEndorsement(pending.getEndorsementRequestId());
        assertEquals(18_000_000L, policy.getFinalPremiumVnd(),
                "premium must reflect locked quoted premium after payment triggers apply");

        // --- Verify: rerate called once (provisional at submit only; approve uses price lock) ---
        verify(pricing, never()).rerate(eq("HEALTH_BASIC"), anyMap());

        // --- Verify: new segment has merged profile ---
        // A6: prior segment closed via saveAndFlush (flushed before the INSERT to avoid
        // a transient overlap tripping the exposure_segment_no_overlap constraint);
        // new segment persisted via save.
        verify(segRepo).saveAndFlush(any(ExposureSegment.class));
        ArgumentCaptor<ExposureSegment> segCaptor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo).save(segCaptor.capture());
        ExposureSegment newSeg = segCaptor.getValue();
        assertEquals(1, newSeg.getExposureSegmentSeq(), "new segment must be seq=1");
        assertEquals(endorseDate, newSeg.getSegmentStart());
        assertEquals(exp, newSeg.getSegmentEnd());
        assertEquals(500_000_000L, newSeg.getCoverageAmountVnd());
        assertEquals(5_000_000L, newSeg.getDeductibleVnd());
        // Verify the merged profile is persisted in the segment's risk snapshot
        String snapshot = newSeg.getRiskSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("\"smoker\":true"), "segment must carry updated smoker=true");
        assertTrue(snapshot.contains("\"bmi\":28"), "segment must carry updated bmi=28");
        assertTrue(snapshot.contains("\"age\":35"), "segment must preserve base age=35");

        // --- Verify: EndorsementApplied event has correct pro-rata info ---
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueue(eq("EndorsementApplied"), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"premium_old\":12000000"),
                "event must carry old premium 12M");
        assertTrue(payload.contains("\"premium_new\":18000000"),
                "event must carry new premium 18M");
        long expectedRemaining = ChronoUnit.DAYS.between(endorseDate, exp);
        long expectedTerm = ChronoUnit.DAYS.between(eff, exp);
        assertTrue(payload.contains("\"remaining_days\":" + expectedRemaining),
                "event must carry remaining_days=" + expectedRemaining);
        assertTrue(payload.contains("\"term_days\":" + expectedTerm),
                "event must carry term_days=" + expectedTerm);
    }

    /**
     * Scenario 2 — Coverage/deductible are blocked from endorsement.
     *
     * <p>Same health policy, customer tries to raise coverage from 500M to 800M.
     * Expected: rejected with INVALID_ENDORSEMENT_ATTRIBUTE (coverage/deductible
     * cannot be changed through endorsement).
     */
    @Test
    void healthEndorsementCoverageOnlyChange_isRejected() {
        Policy policy = activePolicy();
        policy.setProductId("HEALTH_BASIC");
        policy.setFinalPremiumVnd(12_000_000L);

        ExposureSegment base = new ExposureSegment();
        base.setSegmentId(UUID.randomUUID());
        base.setPolicyId(policy.getPolicyId());
        base.setExposureSegmentSeq(0);
        base.setSegmentStart(policy.getPolicyEffectiveDate());
        base.setSegmentEnd(policy.getPolicyExpirationDate());
        base.setCoverageAmountVnd(500_000_000L);
        base.setDeductibleVnd(5_000_000L);
        base.setRiskSnapshot("{\"age\":35,\"smoker\":false,\"bmi\":22,\"gender\":\"Male\"}");

        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        PolicyRepository repo = mock(PolicyRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(base));

        PolicyLifecycleService s = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, mock(BillingClient.class), outbox);

        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("coverage_amount_vnd", 800_000_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(150));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.INVALID_ENDORSEMENT_ATTRIBUTE, ex.getErrorCode());
    }

    /**
     * E2E premium increase branch: customer submits material change → admin approves →
     * AP path (quotedPremium > currentPremium) → netDue >= MIN_SETTLE_AMOUNT → invoice created →
     * APPROVED_PENDING_PAYMENT → payment triggers applyPendingEndorsement → APPLIED.
     *
     * Verifies: price lock (no rerate at apply), invoice creation, EndorsementPendingPayment event,
     * premium updated to locked quoted premium, endorsement status transitions.
     */
    @Test
    void premiumIncreaseFullFlow_apPathWithInvoiceAndPayment() {
        Policy policy = activePolicy();
        policy.setFinalPremiumVnd(1_000_000L);

        ExposureSegment base = new ExposureSegment();
        base.setSegmentId(UUID.randomUUID());
        base.setPolicyId(policy.getPolicyId());
        base.setExposureSegmentSeq(0);
        base.setSegmentStart(policy.getPolicyEffectiveDate());
        base.setSegmentEnd(policy.getPolicyExpirationDate());
        base.setCoverageAmountVnd(300_000_000L);
        base.setDeductibleVnd(500_000L);
        base.setRiskSnapshot("{\"age\":40,\"gender\":\"Male\",\"vehicle_value_vnd\":300000000}");

        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_200_000L));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        PolicyRepository repo = mock(PolicyRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        BillingClient billing = mock(BillingClient.class);

        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(base));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.findByPolicyIdOrderByCreatedAtDesc(policy.getPolicyId())).thenReturn(List.of());

        // Billing net-off is asynchronous in billing when the invoice is created

        PolicyLifecycleService s = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, billing, outbox);

        // --- Step 1: customer submits material change (vehicle_value increase) ---
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("vehicle_value_vnd", 500_000_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("PRICING_PENDING", result.getStatus());
        assertNull(result.getQuotedPremiumVnd(),
                "customer must receive provisional premium at submission");

        // Capture pending entity
        ArgumentCaptor<EndorsementRequestEntity> endCaptor = ArgumentCaptor.forClass(EndorsementRequestEntity.class);
        verify(endRepo).save(endCaptor.capture());
        EndorsementRequestEntity pending = endCaptor.getValue();
        when(endRepo.findByPricingRequestId(pending.getPricingRequestId())).thenReturn(Optional.of(pending));
        s.handleRepriceCompleted(pending.getPricingRequestId().toString(), "ENDORSEMENT_SUBMIT", 2_200_000L, null);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        // --- Step 2: admin approves → AP path (premium increase) ---
        EndorsementRequestResponse approveResp = s.approveEndorsement(pending.getEndorsementRequestId(), ADMIN);

        assertEquals(EndorsementStatus.APPROVED_PENDING_PAYMENT, approveResp.getStatus(),
                "premium increase with netDue >= MIN_SETTLE_AMOUNT must wait for payment");
        assertEquals(ADMIN, approveResp.getReviewedBy());
        assertNull(approveResp.getInvoiceId(),
                "invoice_id must be null — billing creates it asynchronously via EndorsementPendingPayment event");
        assertEquals(1_000_000L, policy.getFinalPremiumVnd(),
                "premium must NOT change until payment is received");

        // Verify EndorsementPendingPayment event emitted with GROSS additional_charge_vnd
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("EndorsementPendingPayment"), eventCaptor.capture());
        String eventPayload = eventCaptor.getValue();
        assertTrue(eventPayload.contains("\"endorsement_request_id\":\"" + pending.getEndorsementRequestId() + "\""),
                "event must carry endorsement_request_id");
        assertTrue(eventPayload.contains("\"invoice_id\":\"\""),
                "event must carry empty invoice_id");
        assertTrue(eventPayload.contains("\"order_id\":\"" + policy.getOrderId() + "\""),
                "event must carry order_id");

        // Verify billing was called for net-off quote (waive decision only)
        // Verify billing was NOT called to create endorsement invoice (async now)
        verify(billing, never()).createEndorsementInvoice(any(), any(), anyLong(), any(), any());
        verify(billing, never()).createEndorsementInvoice(any(), any(), anyLong(), any(), any(), any());

        // Verify rerate called once (submit only, not at approve — price lock)
        verify(pricing, never()).rerate(eq("MOTOR_BASIC"), anyMap());

        // --- Step 3: simulate invoice payment → applyPendingEndorsement ---
        s.applyPendingEndorsement(pending.getEndorsementRequestId());

        assertEquals(EndorsementStatus.APPLIED, pending.getStatus(),
                "endorsement must be APPLIED after payment");
        assertEquals(2_200_000L, policy.getFinalPremiumVnd(),
                "premium must reflect locked quoted premium after apply");

        // Verify EndorsementApplied event emitted with correct premiums
        // A6: prior segment closed via saveAndFlush (ordered before the INSERT to
        // avoid the exposure_segment_no_overlap constraint) + new segment via save.
        verify(segRepo).saveAndFlush(any(ExposureSegment.class));
        verify(segRepo).save(any(ExposureSegment.class));
        verify(outbox).enqueue(eq("EndorsementApplied"), anyString());
    }

    private BillingClient billingMock() {
        BillingClient b = mock(BillingClient.class);
        return b;
    }

    private EndorsementRequestEntity pendingEntity(Policy policy) {
        EndorsementRequestEntity e = new EndorsementRequestEntity();
        e.setEndorsementRequestId(UUID.randomUUID());
        e.setPolicyId(policy.getPolicyId());
        e.setCustomerId(policy.getCustomerId());
        e.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        e.setStatus(EndorsementStatus.PENDING_REVIEW);
        e.setChangeSet("{\"vehicle_value_vnd\":500000000}");
        e.setQuotedPremiumVnd(2_200_000L);
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }
}
