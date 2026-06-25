package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
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
        OffsetDateTime eff = OffsetDateTime.now().minusDays(30);
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
        return new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, mock(OutboxPublisher.class));
    }

    private EndorsementRequest materialRequest(Policy policy) {
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("vehicle_value_vnd", 500_000_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        req.setCoverageAmountVnd(500_000_000L);
        req.setDeductibleVnd(1_000_000L);
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

        assertEquals("pending_review", result.getStatus());
        assertNotNull(result.getEndorsementRequestId());
        assertEquals(2_200_000L, result.getQuotedPremiumVnd(),
                "customer must receive provisional premium at submission time");
        // Re-rate is called for the provisional quote, but the change is NOT applied.
        verify(pricing, times(1)).rerate(eq("MOTOR_BASIC"), anyMap());
        verify(segRepo, never()).save(any(ExposureSegment.class));
        assertEquals(1_000_000L, policy.getFinalPremiumVnd(), "premium must not change before admin approval");
        // A PENDING_REVIEW request must be persisted with the quoted premium.
        ArgumentCaptor<EndorsementRequestEntity> captor = ArgumentCaptor.forClass(EndorsementRequestEntity.class);
        verify(endRepo, times(1)).save(captor.capture());
        assertEquals(EndorsementStatus.PENDING_REVIEW, captor.getValue().getStatus());
        assertEquals(policy.getPolicyId(), captor.getValue().getPolicyId());
        assertEquals(2_200_000L, captor.getValue().getQuotedPremiumVnd(),
                "pending entity must store the provisional premium");
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

        assertEquals(EndorsementStatus.APPROVED, resp.getStatus());
        assertEquals(ADMIN, resp.getReviewedBy());
        assertEquals(2_500_000L, policy.getFinalPremiumVnd(), "premium must reflect re-rating after approval");
        ArgumentCaptor<ExposureSegment> captor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(captor.capture());
        assertEquals(500_000_000L, captor.getValue().getCoverageAmountVnd());
        assertEquals(1_000_000L, captor.getValue().getDeductibleVnd());
        assertEquals(EndorsementStatus.APPROVED, pending.getStatus());
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
    void nonMaterialChangeIsAppliedImmediatelyAndReRates() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 1_200_000L));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementRequest req = new EndorsementRequest();
        req.setChange(new HashMap<>()); // non-material (coverage/deductible only)
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        req.setCoverageAmountVnd(200_000_000L);

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("applied", result.getStatus());
        assertNotNull(result.getPolicy());
        // Non-material changes are still re-rated because coverage/deductible affect premium.
        verify(pricing, times(1)).rerate(eq("MOTOR_BASIC"), anyMap());
        assertEquals(1_200_000L, policy.getFinalPremiumVnd(), "coverage change must re-rate to new premium");
        verify(segRepo, times(1)).save(any(ExposureSegment.class));
        verify(endRepo, never()).save(any(EndorsementRequestEntity.class));
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
                pricing, mock(OutboxPublisher.class));
        EndorsementRequestEntity pending = pendingEntity(policy);
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        s.approveEndorsement(pending.getEndorsementRequestId(), ADMIN);

        // The re-rate profile must merge the base profile with the changed attributes,
        // not carry only the delta.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> profileCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pricing).rerate(eq("MOTOR_BASIC"), profileCaptor.capture());
        Map<String, Object> sent = profileCaptor.getValue();
        assertEquals(40, ((Number) sent.get("age")).intValue(), "base profile field must be preserved");
        assertEquals("Male", sent.get("gender"), "base profile field must be preserved");
        assertEquals(500_000_000L, ((Number) sent.get("vehicle_value_vnd")).longValue(),
                "changed attribute must override the base profile");
        assertEquals(500_000_000L, ((Number) sent.get("coverage_amount_vnd")).longValue());
        assertEquals(1_000_000L, ((Number) sent.get("deductible_vnd")).longValue());
    }

    @Test
    void healthRiskAttributeChangeIsMaterialAndGoesToReview() {
        Policy policy = activePolicy();
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

        assertEquals("pending_review", result.getStatus(), "any risk attribute change must be material");
        // Re-rate is called for the provisional quote, but the change is not applied.
        verify(pricing, times(1)).rerate(eq("MOTOR_BASIC"), anyMap());
        verify(endRepo, times(1)).save(any(EndorsementRequestEntity.class));
    }

    @Test
    void pureCoverageDeductibleChangeIsNonMaterialButStillReRates() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 1_500_000L));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        // Only sum-insured / retention in the change set: non-material (no admin review)
        // but still re-rated because coverage/deductible affect the premium.
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("coverage_amount_vnd", 800_000_000L);
        change.put("deductible_vnd", 500_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("applied", result.getStatus());
        verify(pricing, times(1)).rerate(eq("MOTOR_BASIC"), anyMap());
        verify(endRepo, never()).save(any(EndorsementRequestEntity.class));
        assertEquals(1_500_000L, policy.getFinalPremiumVnd(),
                "coverage/deductible change must re-rate to new premium");
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

        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(base));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Pricing returns 18M for both the provisional quote (submit) and the apply (admin approve).
        when(pricing.rerate(eq("HEALTH_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 18_000_000L));

        PolicyLifecycleService s = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, outbox);

        // --- Step 1: customer submits endorsement (smoker + bmi change) ---
        OffsetDateTime endorseDate = eff.plusDays(104);
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("smoker", true);
        change.put("bmi", 28);
        req.setChange(change);
        req.setEffectiveDate(endorseDate);
        req.setCoverageAmountVnd(500_000_000L);
        req.setDeductibleVnd(5_000_000L);

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("pending_review", result.getStatus(),
                "smoker + bmi change must be material and go to admin review");
        assertEquals(18_000_000L, result.getQuotedPremiumVnd(),
                "customer must receive provisional premium at submission time");
        assertEquals(12_000_000L, policy.getFinalPremiumVnd(),
                "policy premium must not change before admin approval");

        // --- Step 2: admin approves → re-rate and apply ---

        // Capture the pending entity that was saved during endorse()
        ArgumentCaptor<EndorsementRequestEntity> endCaptor = ArgumentCaptor.forClass(EndorsementRequestEntity.class);
        verify(endRepo).save(endCaptor.capture());
        EndorsementRequestEntity pending = endCaptor.getValue();
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        EndorsementRequestResponse approveResp = s.approveEndorsement(pending.getEndorsementRequestId(), ADMIN);

        assertEquals(EndorsementStatus.APPROVED, approveResp.getStatus());
        assertEquals(18_000_000L, policy.getFinalPremiumVnd(),
                "premium must reflect re-rate after admin approval");

        // --- Verify: rerate called twice (provisional at submit + apply at approve) with full merged profile ---
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> profileCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pricing, times(2)).rerate(eq("HEALTH_BASIC"), profileCaptor.capture());
        Map<String, Object> sentProfile = profileCaptor.getValue();
        assertEquals(35, ((Number) sentProfile.get("age")).intValue(), "base age must be preserved");
        assertEquals(true, sentProfile.get("smoker"), "smoker must be overridden to true");
        assertEquals(28, ((Number) sentProfile.get("bmi")).intValue(), "bmi must be overridden to 28");
        assertEquals("Male", sentProfile.get("gender"), "base gender must be preserved");
        assertEquals(500_000_000L, ((Number) sentProfile.get("coverage_amount_vnd")).longValue());
        assertEquals(5_000_000L, ((Number) sentProfile.get("deductible_vnd")).longValue());

        // --- Verify: new segment has merged profile ---
        ArgumentCaptor<ExposureSegment> segCaptor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(segCaptor.capture());
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
     * Scenario 2 — Non-material change: only coverage increase.
     *
     * <p>Same health policy, customer wants to raise coverage from 500M to 800M.
     * Expected: applied immediately (no admin review), no re-rate, premium
     * unchanged, new segment records the higher coverage.
     */
    @Test
    void healthEndorsementCoverageOnlyChange_appliedImmediately() {
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
        when(pricing.rerate(eq("HEALTH_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 15_000_000L));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        PolicyRepository repo = mock(PolicyRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(base));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService s = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, outbox);

        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("coverage_amount_vnd", 800_000_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(150));
        req.setCoverageAmountVnd(800_000_000L);
        req.setDeductibleVnd(5_000_000L);

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("applied", result.getStatus(),
                "coverage-only change must be applied immediately (no admin review)");
        // Coverage change still triggers re-rate because coverage_amount_vnd is a model feature.
        verify(pricing, times(1)).rerate(eq("HEALTH_BASIC"), anyMap());
        verify(endRepo, never()).save(any(EndorsementRequestEntity.class));
        assertEquals(15_000_000L, policy.getFinalPremiumVnd(),
                "coverage change must re-rate to new premium");

        // New segment must reflect the increased coverage
        ArgumentCaptor<ExposureSegment> segCaptor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(segCaptor.capture());
        assertEquals(800_000_000L, segCaptor.getValue().getCoverageAmountVnd(),
                "segment must carry the new coverage 800M");
        assertEquals(5_000_000L, segCaptor.getValue().getDeductibleVnd(),
                "deductible must be preserved");
    }

    private EndorsementRequestEntity pendingEntity(Policy policy) {
        EndorsementRequestEntity e = new EndorsementRequestEntity();
        e.setEndorsementRequestId(UUID.randomUUID());
        e.setPolicyId(policy.getPolicyId());
        e.setCustomerId(policy.getCustomerId());
        e.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        e.setCoverageAmountVnd(500_000_000L);
        e.setDeductibleVnd(1_000_000L);
        e.setStatus(EndorsementStatus.PENDING_REVIEW);
        e.setChangeSet("{\"vehicle_value_vnd\":500000000}");
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }
}
