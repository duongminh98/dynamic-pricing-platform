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
    void customerMaterialChangeGoesToPendingReviewAndIsNotApplied() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementResult result = s.endorse(policy.getPolicyId(), materialRequest(policy), SUBJECT);

        assertEquals("pending_review", result.getStatus());
        assertNotNull(result.getEndorsementRequestId());
        // The material change must NOT be applied by the customer call.
        verify(pricing, never()).rerate(anyString(), anyMap());
        verify(segRepo, never()).save(any(ExposureSegment.class));
        assertEquals(1_000_000L, policy.getFinalPremiumVnd(), "premium must not change before admin approval");
        // A PENDING_REVIEW request must be persisted.
        ArgumentCaptor<EndorsementRequestEntity> captor = ArgumentCaptor.forClass(EndorsementRequestEntity.class);
        verify(endRepo, times(1)).save(captor.capture());
        assertEquals(EndorsementStatus.PENDING_REVIEW, captor.getValue().getStatus());
        assertEquals(policy.getPolicyId(), captor.getValue().getPolicyId());
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
    void nonMaterialChangeIsAppliedImmediately() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        EndorsementRequest req = new EndorsementRequest();
        req.setChange(new HashMap<>()); // non-material
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        req.setCoverageAmountVnd(200_000_000L);

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("applied", result.getStatus());
        assertNotNull(result.getPolicy());
        assertEquals(1_000_000L, policy.getFinalPremiumVnd(), "non-material change must not re-rate");
        verify(pricing, never()).rerate(anyString(), anyMap());
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
        verify(pricing, never()).rerate(anyString(), anyMap());
        verify(endRepo, times(1)).save(any(EndorsementRequestEntity.class));
    }

    @Test
    void pureCoverageDeductibleChangeIsNonMaterial() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        EndorsementRequestRepository endRepo = mock(EndorsementRequestRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class), endRepo);

        // Only sum-insured / retention in the change set: no re-rate, applied at once.
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("coverage_amount_vnd", 800_000_000L);
        change.put("deductible_vnd", 500_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));

        EndorsementResult result = s.endorse(policy.getPolicyId(), req, SUBJECT);

        assertEquals("applied", result.getStatus());
        verify(pricing, never()).rerate(anyString(), anyMap());
        verify(endRepo, never()).save(any(EndorsementRequestEntity.class));
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
