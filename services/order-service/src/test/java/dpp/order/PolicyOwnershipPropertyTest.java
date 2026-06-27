package dpp.order;

import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.dto.CancelRequest;
import dpp.order.dto.EndorsementRequest;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import net.jqwik.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 13")
class PolicyOwnershipPropertyTest {

    private Policy activePolicy(UUID customerId) {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(customerId);
        p.setProductId("motor-basic");
        p.setStatus(PolicyStatus.active);
        p.setPolicyEffectiveDate(OffsetDateTime.now().minusDays(30));
        p.setPolicyExpirationDate(OffsetDateTime.now().plusDays(335));
        p.setRenewalNumber(0);
        p.setRenewal(false);
        p.setYearsSinceFirstPolicy(0);
        p.setPolicyCountPrior(0);
        p.setFinalPremiumVnd(1_000_000L);
        p.setCreatedAt(OffsetDateTime.now());
        return p;
    }

    private PolicyLifecycleService newService(PolicyRepository repo) {
        return new PolicyLifecycleService(repo, mock(ExposureSegmentRepository.class),
                mock(PolicyDocumentRepository.class), mock(EndorsementRequestRepository.class),
                mock(PricingClient.class), mock(BillingClient.class), mock(OutboxPublisher.class));
    }

    @Property(tries = 100)
    void crossCustomerEndorsementRejected(@ForAll int seed) {
        UUID ownerCustomerId = UUID.nameUUIDFromBytes("owner-subject".getBytes());
        UUID policyId = UUID.randomUUID();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(activePolicy(ownerCustomerId)));

        PolicyLifecycleService svc = newService(repo);
        EndorsementRequest req = new EndorsementRequest();
        req.setEffectiveDate(OffsetDateTime.now());
        req.setChange(new HashMap<>());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.endorse(policyId, req, "intruder-subject"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Property(tries = 100)
    void crossCustomerCancelRejected(@ForAll int seed) {
        UUID ownerCustomerId = UUID.nameUUIDFromBytes("owner-subject".getBytes());
        UUID policyId = UUID.randomUUID();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(activePolicy(ownerCustomerId)));

        PolicyLifecycleService svc = newService(repo);
        CancelRequest req = new CancelRequest();
        req.setCancelDate(OffsetDateTime.now());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.cancel(policyId, req, "intruder-subject"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Property(tries = 100)
    void crossCustomerRenewRejected(@ForAll int seed) {
        UUID ownerCustomerId = UUID.nameUUIDFromBytes("owner-subject".getBytes());
        UUID policyId = UUID.randomUUID();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(activePolicy(ownerCustomerId)));

        PolicyLifecycleService svc = newService(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.renew(policyId, "intruder-subject"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Property(tries = 100)
    void nonExistentPolicyRejected(@ForAll int seed) {
        UUID policyId = UUID.randomUUID();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.empty());

        PolicyLifecycleService svc = newService(repo);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> svc.renew(policyId, "some-subject"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void property13_sanity() {
        String subject = "owner-subject";
        UUID customerId = UUID.nameUUIDFromBytes(subject.getBytes());
        UUID policyId = UUID.randomUUID();
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findById(policyId)).thenReturn(Optional.of(activePolicy(customerId)));
        when(repo.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyLifecycleService svc = newService(repo);
        assertDoesNotThrow(() -> svc.renew(policyId, subject));
    }
}
