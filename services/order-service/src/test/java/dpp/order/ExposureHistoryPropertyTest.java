package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.dto.EndorsementRequest;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 10")
class ExposureHistoryPropertyTest {

    private Policy activePolicy(UUID customerId) {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setCustomerId(customerId);
        p.setStatus(PolicyStatus.active);
        OffsetDateTime eff = OffsetDateTime.now().minusDays(30);
        p.setPolicyEffectiveDate(eff);
        p.setPolicyExpirationDate(eff.plus(365, ChronoUnit.DAYS));
        p.setFinalPremiumVnd(1_000_000L);
        return p;
    }

    private ExposureSegment segment(UUID policyId, int seq) {
        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policyId);
        seg.setExposureSegmentSeq(seq);
        seg.setSegmentStart(OffsetDateTime.now().minusDays(30 + seq));
        seg.setSegmentEnd(OffsetDateTime.now().plusDays(335 - seq));
        seg.setEarnedExposureYears(0.5);
        seg.setCoverageAmountVnd(0);
        seg.setDeductibleVnd(0);
        seg.setRiskSnapshot("{}");
        return seg;
    }

    private PolicyLifecycleService newService(PolicyRepository repo, ExposureSegmentRepository segRepo,
                                               PolicyDocumentRepository docRepo) {
        return new PolicyLifecycleService(repo, segRepo, docRepo, mock(OutboxPublisher.class));
    }

    @Property(tries = 100)
    void endorsementAddsSegmentWithIncrementedSeqAndKeepsOld(
            @ForAll @IntRange(min = 1, max = 10) int existingCount) {
        String subject = "owner-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        Policy policy = activePolicy(customerId);
        policy.setPolicyId(policyId);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(policy));

        List<ExposureSegment> existingSegments = new ArrayList<>();
        for (int i = 0; i < existingCount; i++) {
            existingSegments.add(segment(policyId, i));
        }
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId)).thenReturn(existingSegments);
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.findByPolicyIdOrderByVersionDesc(policyId)).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = newService(repo, segRepo, docRepo);
        EndorsementRequest req = new EndorsementRequest();
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        req.setChange(new HashMap<>());

        svc.endorse(policyId, req, subject);

        ArgumentCaptor<ExposureSegment> captor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(captor.capture());
        ExposureSegment newSeg = captor.getValue();
        assertEquals(existingCount, newSeg.getExposureSegmentSeq());
        assertEquals(policyId, newSeg.getPolicyId());
        assertTrue(newSeg.getEarnedExposureYears() > 0);

        verify(segRepo, never()).delete(any());
        verify(segRepo, never()).deleteAll();
    }

    @Property(tries = 100)
    void endorsementDateOutsideCoverageRejected(@ForAll int seed) {
        String subject = "owner-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        Policy policy = activePolicy(customerId);
        policy.setPolicyId(policyId);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(policy));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId)).thenReturn(List.of());

        PolicyLifecycleService svc = newService(repo, segRepo, mock(PolicyDocumentRepository.class));
        EndorsementRequest req = new EndorsementRequest();
        req.setEffectiveDate(policy.getPolicyExpirationDate().plusDays(10));
        req.setChange(new HashMap<>());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.endorse(policyId, req, subject));
        assertEquals(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE, ex.getErrorCode());
        verify(segRepo, never()).save(any());
    }

    @Test
    void property10_sanity() {
        String subject = "owner-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();
        Policy policy = activePolicy(customerId);
        policy.setPolicyId(policyId);

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(policy));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId)).thenReturn(List.of(segment(policyId, 0)));
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.findByPolicyIdOrderByVersionDesc(policyId)).thenReturn(List.of());
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = newService(repo, segRepo, docRepo);
        EndorsementRequest req = new EndorsementRequest();
        req.setEffectiveDate(policy.getPolicyEffectiveDate().plusDays(10));
        req.setChange(new HashMap<>());

        assertDoesNotThrow(() -> svc.endorse(policyId, req, subject));
    }
}
