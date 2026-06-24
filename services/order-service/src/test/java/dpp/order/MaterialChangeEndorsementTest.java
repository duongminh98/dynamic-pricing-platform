package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.PricingClient;
import dpp.order.dto.EndorsementRequest;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
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
 * Material_Change endorsement behaviour (R23.2, R23.7, R23.8, R23.9, BR-21):
 * admin re-review gate + Pricing re-rating of the remaining term.
 */
@Tag("Feature: dynamic-pricing-platform, Property 10")
class MaterialChangeEndorsementTest {

    private static final String SUBJECT = "owner-subject";

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
                                       PolicyDocumentRepository docRepo, PolicyRepository repo) {
        when(repo.findById(policy.getPolicyId())).thenReturn(Optional.of(policy));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policy.getPolicyId())).thenReturn(List.of());
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.findByPolicyIdOrderByVersionDesc(policy.getPolicyId())).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new PolicyLifecycleService(repo, segRepo, docRepo, pricing, mock(OutboxPublisher.class));
    }

    private EndorsementRequest materialRequest(Policy policy, String decision) {
        EndorsementRequest req = new EndorsementRequest();
        Map<String, Object> change = new HashMap<>();
        change.put("vehicle_value_vnd", 500_000_000L);
        req.setChange(change);
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        req.setReviewDecision(decision);
        req.setCoverageAmountVnd(500_000_000L);
        req.setDeductibleVnd(1_000_000L);
        return req;
    }

    @Test
    void materialChangeWithoutApprovalIsRejected() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        PolicyLifecycleService s = svc(policy, pricing, mock(ExposureSegmentRepository.class),
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), materialRequest(policy, null), SUBJECT));
        assertEquals(ErrorCode.ORDER_NOT_APPROVED, ex.getErrorCode());
        verify(pricing, never()).rerate(anyString(), anyMap());
    }

    @Test
    void materialChangeRejectedByAdmin() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        PolicyLifecycleService s = svc(policy, pricing, mock(ExposureSegmentRepository.class),
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> s.endorse(policy.getPolicyId(), materialRequest(policy, "REJECT"), SUBJECT));
        assertEquals(ErrorCode.ORDER_NOT_APPROVED, ex.getErrorCode());
    }

    @Test
    void materialChangeApprovedReRatesAndUpdatesPremium() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        when(pricing.rerate(eq("MOTOR_BASIC"), anyMap()))
                .thenReturn(Map.of("final_premium_vnd", 2_500_000L));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class));

        s.endorse(policy.getPolicyId(), materialRequest(policy, "APPROVE"), SUBJECT);

        assertEquals(2_500_000L, policy.getFinalPremiumVnd(), "premium must reflect re-rating");
        ArgumentCaptor<ExposureSegment> captor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(captor.capture());
        assertEquals(500_000_000L, captor.getValue().getCoverageAmountVnd());
        assertEquals(1_000_000L, captor.getValue().getDeductibleVnd());
    }

    @Test
    void nonMaterialChangeSkipsReReview() {
        Policy policy = activePolicy();
        PricingClient pricing = mock(PricingClient.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        PolicyLifecycleService s = svc(policy, pricing, segRepo,
                mock(PolicyDocumentRepository.class), mock(PolicyRepository.class));

        EndorsementRequest req = new EndorsementRequest();
        req.setChange(new HashMap<>()); // non-material
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));

        assertDoesNotThrow(() -> s.endorse(policy.getPolicyId(), req, SUBJECT));
        assertEquals(1_000_000L, policy.getFinalPremiumVnd(), "non-material change must not re-rate");
        verify(pricing, never()).rerate(anyString(), anyMap());
    }
}
