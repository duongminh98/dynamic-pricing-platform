package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.PricingClient;
import dpp.order.dto.CancelRequest;
import dpp.order.dto.EndorsementRequest;
import dpp.order.dto.EndorsementRequestResponse;
import dpp.order.dto.EndorsementResult;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.EndorsementStatus;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyDocument;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Renewal re-rating + cancellation exposure cut (R24.2, R25.3). */
class RenewalAndCancellationTest {

    private static final String SUBJECT = "owner-subject";

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
        p.setRenewalNumber(0);
        p.setRenewal(false);
        p.setYearsSinceFirstPolicy(0);
        p.setPolicyCountPrior(0);
        p.setCreatedAt(OffsetDateTime.now());
        return p;
    }

    @Test
    void renewalReRatesPremiumWithFullProfileAndCreatesSegment() {
        Policy old = activePolicy();
        old.setPolicyExpirationDate(OffsetDateTime.now().minusDays(1)); // expired -> renew now

        ExposureSegment oldSeg = new ExposureSegment();
        oldSeg.setSegmentId(UUID.randomUUID());
        oldSeg.setPolicyId(old.getPolicyId());
        oldSeg.setExposureSegmentSeq(0);
        oldSeg.setSegmentStart(old.getPolicyEffectiveDate());
        oldSeg.setSegmentEnd(old.getPolicyExpirationDate());
        oldSeg.setCoverageAmountVnd(200_000_000L);
        oldSeg.setDeductibleVnd(1_000_000L);
        oldSeg.setRiskSnapshot("{\"age\":30,\"vehicle_value_vnd\":400000000}");

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId())).thenReturn(List.of(oldSeg));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap())).thenReturn(Map.of("final_premium_vnd", 1_750_000L));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, segRepo,
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                pricing, mock(OutboxPublisher.class));

        PolicyResponse resp = svc.renew(old.getPolicyId(), SUBJECT);

        assertEquals(1_750_000L, resp.getFinalPremiumVnd(), "renewal premium must come from re-rating, not cloned");
        assertTrue(resp.isRenewal(), "renewed policy must be flagged as renewal");
        assertEquals(1, resp.getRenewalNumber(), "renewal_number must increment");
        verify(pricing, times(1)).rerate(eq("MOTOR_BASIC"), anyMap());

        // Verify re-rate was called with full profile (age + vehicle_value from old segment + renewal context)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> profileCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pricing).rerate(eq("MOTOR_BASIC"), profileCaptor.capture());
        Map<String, Object> sentProfile = profileCaptor.getValue();
        assertEquals(30, ((Number) sentProfile.get("age")).intValue(), "base age must be preserved from old segment");
        assertEquals(400_000_000L, ((Number) sentProfile.get("vehicle_value_vnd")).longValue(),
                "vehicle_value must be preserved from old segment");
        assertEquals(true, sentProfile.get("is_renewal"), "renewal flag must be set");
        assertEquals(1, ((Number) sentProfile.get("renewal_number")).intValue(), "renewal_number must be 1");

        // Verify exposure segment 0 was created for the renewed policy
        ArgumentCaptor<ExposureSegment> segCaptor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(segCaptor.capture());
        ExposureSegment newSeg = segCaptor.getValue();
        assertEquals(0, newSeg.getExposureSegmentSeq(), "renewed policy must have segment seq=0");
        assertEquals(200_000_000L, newSeg.getCoverageAmountVnd(), "coverage must be carried over");
        String snapshot = newSeg.getRiskSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("\"age\":30"), "segment must carry base age");
        assertTrue(snapshot.contains("\"is_renewal\":true"), "segment must carry renewal flag");
    }

    @Test
    void cancellationCutsCoveringSegmentAndUsesCancelDateError() {
        Policy policy = activePolicy();
        OffsetDateTime eff = policy.getPolicyEffectiveDate();
        OffsetDateTime exp = policy.getPolicyExpirationDate();
        OffsetDateTime cancelDate = eff.plusDays(100);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policy.getPolicyId());
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(eff);
        seg.setSegmentEnd(exp);
        seg.setEarnedExposureYears(1.0);
        seg.setCoverageAmountVnd(100_000_000L);
        seg.setDeductibleVnd(0L);
        seg.setRiskSnapshot("{}");
        List<ExposureSegment> segs = new ArrayList<>(List.of(seg));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId())).thenReturn(segs);
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, segRepo,
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                mock(PricingClient.class), mock(OutboxPublisher.class));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(cancelDate);
        svc.cancel(policy.getPolicyId(), req, SUBJECT);

        ArgumentCaptor<ExposureSegment> captor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(captor.capture());
        ExposureSegment cut = captor.getValue();
        assertEquals(cancelDate, cut.getSegmentEnd(), "segment must be cut at cancel_date");
        double expectedYears = Math.max(0, ChronoUnit.DAYS.between(eff, cancelDate)) / 365.25;
        assertEquals(expectedYears, cut.getEarnedExposureYears(), 1e-9);
    }

    @Test
    void cancelDateOutsideRangeUsesCancelDateError() {
        Policy policy = activePolicy();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, mock(ExposureSegmentRepository.class),
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                mock(PricingClient.class), mock(OutboxPublisher.class));

        CancelRequest req = new CancelRequest();
        req.setCancelDate(policy.getPolicyExpirationDate().plusDays(5));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.cancel(policy.getPolicyId(), req, SUBJECT));
        assertEquals(ErrorCode.CANCEL_DATE_OUT_OF_RANGE, ex.getErrorCode());
    }

    // ── Real-world scenario 1: Renewal motor + endorsement on renewed policy ──

    /**
     * Scenario 1 — Motor policy renewal followed by endorsement on the renewed policy.
     *
     * <p>Customer bought a motor policy (age=30, vehicle_value=400M, premium=1M).
     * After 1 year the policy expires. Customer renews → pricing re-rates with the
     * full risk profile + renewal context → premium 1.75M. Billing creates an
     * invoice for the renewal premium. Then the customer endorses the renewed
     * policy to increase vehicle_value to 500M → re-rate → premium changes.
     *
     * Verifies:
     * - Renewal re-rate uses full profile from old segment (age, vehicle_value)
     * - Renewal creates segment 0 with merged profile
     * - PolicyRenewed event carries order_id + final_premium_vnd
     * - Endorsement on renewed policy merges against segment 0
     */
    @Test
    void renewalMotorThenEndorsementOnRenewedPolicy_fullFlow() throws Exception {
        // --- Setup: expired motor policy with base profile segment ---
        Policy old = activePolicy();
        old.setProductId("MOTOR_BASIC");
        old.setFinalPremiumVnd(1_000_000L);
        old.setPolicyExpirationDate(OffsetDateTime.now().minusDays(1)); // expired

        ExposureSegment oldSeg = new ExposureSegment();
        oldSeg.setSegmentId(UUID.randomUUID());
        oldSeg.setPolicyId(old.getPolicyId());
        oldSeg.setExposureSegmentSeq(0);
        oldSeg.setSegmentStart(old.getPolicyEffectiveDate());
        oldSeg.setSegmentEnd(old.getPolicyExpirationDate());
        oldSeg.setCoverageAmountVnd(300_000_000L);
        oldSeg.setDeductibleVnd(2_000_000L);
        oldSeg.setRiskSnapshot("{\"age\":30,\"vehicle_value_vnd\":400000000,\"gender\":\"Male\"}");

        PolicyRepository repo = mock(PolicyRepository.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        when(repo.findById(old.getPolicyId())).thenReturn(Optional.of(old));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(old.getPolicyId()))
                .thenReturn(List.of(oldSeg));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(any())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Renewal re-rate returns 1.75M
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 1_750_000L));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, outbox);

        // --- Step 1: Customer renews ---
        PolicyResponse renewResp = svc.renew(old.getPolicyId(), SUBJECT);

        assertEquals(1_750_000L, renewResp.getFinalPremiumVnd(),
                "renewal premium must come from re-rate with full profile");
        assertTrue(renewResp.isRenewal(), "renewed policy must be flagged as renewal");
        assertEquals(1, renewResp.getRenewalNumber(), "renewal_number must be 1");

        // Verify re-rate was called with full profile from old segment
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> renewProfileCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pricing, times(1)).rerate(eq("MOTOR_BASIC"), renewProfileCaptor.capture());
        Map<String, Object> renewProfile = renewProfileCaptor.getValue();
        assertEquals(30, ((Number) renewProfile.get("age")).intValue(), "age must be preserved from old segment");
        assertEquals(400_000_000L, ((Number) renewProfile.get("vehicle_value_vnd")).longValue(),
                "vehicle_value must be preserved from old segment");
        assertEquals(true, renewProfile.get("is_renewal"), "is_renewal must be set");
        assertEquals(1, ((Number) renewProfile.get("renewal_number")).intValue(), "renewal_number must be 1");

        // Verify segment 0 was created for the renewed policy
        ArgumentCaptor<ExposureSegment> renewSegCaptor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(renewSegCaptor.capture());
        ExposureSegment renewedSeg = renewSegCaptor.getValue();
        assertEquals(0, renewedSeg.getExposureSegmentSeq(), "renewed policy must have segment seq=0");
        assertEquals(300_000_000L, renewedSeg.getCoverageAmountVnd(), "coverage must be carried over");
        String renewedSnapshot = renewedSeg.getRiskSnapshot();
        assertNotNull(renewedSnapshot);
        assertTrue(renewedSnapshot.contains("\"age\":30"), "segment must carry base age");
        assertTrue(renewedSnapshot.contains("\"is_renewal\":true"), "segment must carry renewal flag");

        // Verify PolicyRenewed event carries order_id + final_premium_vnd
        ArgumentCaptor<String> renewPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueue(eq("PolicyRenewed"), renewPayloadCaptor.capture());
        String renewPayload = renewPayloadCaptor.getValue();
        assertTrue(renewPayload.contains("\"final_premium_vnd\":1750000"),
                "event must carry renewal premium 1.75M");
        assertTrue(renewPayload.contains("\"order_id\""),
                "event must carry order_id for billing to create invoice");

        // Capture the renewed policy for step 2
        ArgumentCaptor<Policy> policyCaptor = ArgumentCaptor.forClass(Policy.class);
        verify(repo, times(1)).save(policyCaptor.capture());
        Policy renewedPolicy = policyCaptor.getValue();

        // --- Step 2: Customer endorses renewed policy (change vehicle_value) ---
        // Now mock the repo to return the renewed policy for endorsement
        when(repo.findById(renewedPolicy.getPolicyId())).thenReturn(Optional.of(renewedPolicy));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(renewedPolicy.getPolicyId()))
                .thenReturn(List.of(renewedSeg));

        // Endorsement re-rate returns 2.2M (higher vehicle value → higher premium)
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_200_000L));

        UUID renewedPolicyId = renewedPolicy.getPolicyId();
        EndorsementRequest endorseReq = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("vehicle_value_vnd", 500_000_000L);
        endorseReq.setChange(change);
        endorseReq.setEffectiveDate(renewedPolicy.getPolicyEffectiveDate().plusDays(30));
        endorseReq.setCoverageAmountVnd(300_000_000L);
        endorseReq.setDeductibleVnd(2_000_000L);

        EndorsementResult endorseResult = svc.endorse(renewedPolicyId, endorseReq, SUBJECT);

        assertEquals("pending_review", endorseResult.getStatus(),
                "vehicle_value change is material, goes to admin review");
        assertEquals(2_200_000L, endorseResult.getQuotedPremiumVnd(),
                "customer must receive provisional premium at submission");
        assertEquals(1_750_000L, renewedPolicy.getFinalPremiumVnd(),
                "policy premium must not change before admin approval");

        // Capture pending endorsement and approve it
        ArgumentCaptor<EndorsementRequestEntity> endorseEndCaptor = ArgumentCaptor.forClass(EndorsementRequestEntity.class);
        verify(endRepo).save(endorseEndCaptor.capture());
        EndorsementRequestEntity endorsePending = endorseEndCaptor.getValue();
        when(endRepo.findById(endorsePending.getEndorsementRequestId())).thenReturn(Optional.of(endorsePending));

        EndorsementRequestResponse approveResp = svc.approveEndorsement(endorsePending.getEndorsementRequestId(), "admin-subject");
        assertEquals(EndorsementStatus.APPROVED, approveResp.getStatus());

        // Verify endorsement re-rate used merged profile from segment 0 of renewed policy
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> endorseProfileCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pricing, times(3)).rerate(eq("MOTOR_BASIC"), endorseProfileCaptor.capture());
        Map<String, Object> endorseProfile = endorseProfileCaptor.getValue();
        assertEquals(30, ((Number) endorseProfile.get("age")).intValue(),
                "endorsement must preserve age from renewed segment 0");
        assertEquals(500_000_000L, ((Number) endorseProfile.get("vehicle_value_vnd")).longValue(),
                "endorsement must override vehicle_value to 500M");
        assertEquals(true, endorseProfile.get("is_renewal"),
                "endorsement must preserve is_renewal from renewed segment 0");

        // Verify premium changed after admin approval
        assertEquals(2_200_000L, renewedPolicy.getFinalPremiumVnd(),
                "endorsement on renewed policy must update premium after admin approval");

        // Verify EndorsementApplied event has correct premiums
        ArgumentCaptor<String> endorsePayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueue(eq("EndorsementApplied"), endorsePayloadCaptor.capture());
        String endorsePayload = endorsePayloadCaptor.getValue();
        assertTrue(endorsePayload.contains("\"premium_old\":1750000"),
                "event must carry old premium 1.75M");
        assertTrue(endorsePayload.contains("\"premium_new\":2200000"),
                "event must carry new premium 2.2M");
    }

    // ── Real-world scenario 2: Renewal health + endorsement before renewal ──

    /**
     * Scenario 2 — Health policy endorsement (smoker change) followed by renewal.
     *
     * <p>Customer has a health policy (age=35, smoker=false, bmi=22, premium=12M).
     * On day 360 of 365 they declare smoker=true → material change → admin approves
     * → re-rate → premium 18M → new segment carries smoker=true. Then the policy
     * expires and the customer renews → renewal re-rate must read the latest segment
     * (smoker=true) and produce a premium that reflects the updated risk.
     *
     * Verifies:
     * - Endorsement before renewal updates the last segment with smoker=true
     * - Renewal reads the updated segment → re-rate with smoker=true
     * - Renewal premium reflects the updated risk profile
     */
    @Test
    void healthEndorsementThenRenewal_fullFlow() throws Exception {
        // --- Setup: active health policy near expiry ---
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

        PolicyRepository repo = mock(PolicyRepository.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(base));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(endRepo.save(any(EndorsementRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Endorsement re-rate returns 18M (smoker = higher risk)
        when(pricing.rerate(eq("HEALTH_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 18_000_000L));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo, segRepo, docRepo, endRepo, pricing, outbox);

        // --- Step 1: Customer endorses (smoker=true) on day 360 ---
        OffsetDateTime endorseDate = eff.plusDays(360);
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("smoker", true);
        req.setChange(change);
        req.setEffectiveDate(endorseDate);
        req.setCoverageAmountVnd(500_000_000L);
        req.setDeductibleVnd(5_000_000L);

        EndorsementResult endorseResult = svc.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("pending_review", endorseResult.getStatus(),
                "smoker change must be material and go to admin review");
        assertEquals(18_000_000L, endorseResult.getQuotedPremiumVnd(),
                "customer must receive provisional premium at submission");
        assertEquals(12_000_000L, policy.getFinalPremiumVnd(),
                "policy premium must not change before admin approval");

        // Capture pending endorsement entity
        ArgumentCaptor<EndorsementRequestEntity> endCaptor = ArgumentCaptor.forClass(EndorsementRequestEntity.class);
        verify(endRepo).save(endCaptor.capture());
        EndorsementRequestEntity pending = endCaptor.getValue();
        when(endRepo.findById(pending.getEndorsementRequestId())).thenReturn(Optional.of(pending));

        // --- Step 2: Admin approves → re-rate → new segment with smoker=true ---
        EndorsementRequestResponse approveResp = svc.approveEndorsement(pending.getEndorsementRequestId(), "admin-subject");

        assertEquals(EndorsementStatus.APPROVED, approveResp.getStatus());
        assertEquals(18_000_000L, policy.getFinalPremiumVnd(),
                "premium must reflect re-rate after admin approval");

        // Capture the new segment created by endorsement
        ArgumentCaptor<ExposureSegment> endSegCaptor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(endSegCaptor.capture());
        ExposureSegment endorsedSeg = endSegCaptor.getValue();
        assertEquals(1, endorsedSeg.getExposureSegmentSeq(), "endorsement must create segment seq=1");
        String endorsedSnapshot = endorsedSeg.getRiskSnapshot();
        assertNotNull(endorsedSnapshot);
        assertTrue(endorsedSnapshot.contains("\"smoker\":true"), "segment must carry updated smoker=true");
        assertTrue(endorsedSnapshot.contains("\"age\":35"), "segment must preserve base age=35");

        // --- Step 3: Policy expires, customer renews ---
        // Expire the policy
        policy.setPolicyExpirationDate(OffsetDateTime.now().minusDays(1));

        // Now segRepo returns both segments (base + endorsed) for the old policy
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId()))
                .thenReturn(List.of(base, endorsedSeg));

        // Renewal re-rate returns 19M (smoker=true + renewal context = higher than 18M)
        when(pricing.rerate(eq("HEALTH_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 19_000_000L));

        PolicyResponse renewResp = svc.renew(policy.getPolicyId(), SUBJECT);

        assertTrue(renewResp.isRenewal(), "renewed policy must be flagged as renewal");
        assertEquals(19_000_000L, renewResp.getFinalPremiumVnd(),
                "renewal premium must reflect updated risk (smoker=true) from latest segment");

        // Verify renewal re-rate used the LATEST segment (smoker=true), not the base (smoker=false)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> renewProfileCaptor = ArgumentCaptor.forClass(Map.class);
        // pricing.rerate called 3 times: 1 provisional + 1 apply (endorsement) + 1 renewal
        verify(pricing, times(3)).rerate(eq("HEALTH_BASIC"), renewProfileCaptor.capture());
        Map<String, Object> renewProfile = renewProfileCaptor.getValue();
        assertEquals(true, renewProfile.get("smoker"),
                "renewal must read smoker=true from the latest segment (after endorsement)");
        assertEquals(35, ((Number) renewProfile.get("age")).intValue(), "age must be preserved");
        assertEquals(true, renewProfile.get("is_renewal"), "is_renewal must be set");

        // Verify renewal created segment 0 for the new policy with updated profile
        ArgumentCaptor<ExposureSegment> renewSegCaptor = ArgumentCaptor.forClass(ExposureSegment.class);
        // segRepo.save called twice: 1 for endorsement + 1 for renewal
        verify(segRepo, times(2)).save(renewSegCaptor.capture());
        ExposureSegment renewalSeg = renewSegCaptor.getValue();
        assertEquals(0, renewalSeg.getExposureSegmentSeq(), "renewed policy must have segment seq=0");
        String renewalSnapshot = renewalSeg.getRiskSnapshot();
        assertNotNull(renewalSnapshot);
        assertTrue(renewalSnapshot.contains("\"smoker\":true"),
                "renewed segment must carry smoker=true from the endorsement");
        assertTrue(renewalSnapshot.contains("\"is_renewal\":true"),
                "renewed segment must carry renewal flag");
    }
}
