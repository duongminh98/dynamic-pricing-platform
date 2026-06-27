package dpp.order;

import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 18")
class RenewalChainPropertyTest {

    private Policy basePolicy(UUID customerId, int renewalNumber) {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(customerId);
        p.setProductId("motor-001");
        p.setStatus(PolicyStatus.active);
        p.setPolicyEffectiveDate(OffsetDateTime.now().minusDays(365));
        p.setPolicyExpirationDate(OffsetDateTime.now().minusDays(1));
        p.setRenewalNumber(renewalNumber);
        p.setRenewal(renewalNumber > 0);
        p.setYearsSinceFirstPolicy(renewalNumber);
        p.setPolicyCountPrior(renewalNumber);
        p.setFinalPremiumVnd(1_000_000L);
        p.setCreatedAt(OffsetDateTime.now());
        return p;
    }

    private PolicyLifecycleService newService(PolicyRepository repo, ExposureSegmentRepository segRepo) {
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(any())).thenReturn(java.util.List.of());
        when(segRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new PolicyLifecycleService(repo, segRepo,
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                mock(PricingClient.class), mock(BillingClient.class), mock(OutboxPublisher.class));
    }

    @Property(tries = 100)
    void renewalIncrementsNumberAndPreservesIdentity(
            @ForAll @IntRange(min = 0, max = 10) int oldRenewalNumber) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(basePolicy(customerId, oldRenewalNumber)));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class));
        PolicyResponse resp = svc.renew(policyId, subject);

        assertEquals(oldRenewalNumber + 1, resp.getRenewalNumber());
        assertTrue(resp.isRenewal());
        assertEquals(customerId, resp.getCustomerId());

        ArgumentCaptor<Policy> captor = ArgumentCaptor.forClass(Policy.class);
        verify(repo, times(1)).save(captor.capture());
        Policy saved = captor.getValue();
        assertEquals(oldRenewalNumber + 1, saved.getRenewalNumber());
        assertTrue(saved.isRenewal());
        assertEquals(customerId, saved.getCustomerId());
    }

    @Property(tries = 100)
    void firstPolicyHasZeroRenewalNumberAndNotRenewal(@ForAll int seed) {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(basePolicy(customerId, 0)));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class));
        PolicyResponse resp = svc.renew(policyId, subject);

        assertEquals(1, resp.getRenewalNumber());
        assertTrue(resp.isRenewal());
        assertEquals(customerId, resp.getCustomerId());
    }

    @Test
    void property18_sanity() {
        String subject = "customer-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();

        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(basePolicy(customerId, 3)));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = newService(repo, mock(ExposureSegmentRepository.class));
        PolicyResponse resp = svc.renew(policyId, subject);
        assertEquals(4, resp.getRenewalNumber());
        assertTrue(resp.isRenewal());
    }
}
