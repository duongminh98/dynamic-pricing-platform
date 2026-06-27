package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.order.dto.PolicyResponse;
import dpp.order.dto.PageResponse;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminPolicyPagingTest {

    private Policy policy(UUID id, UUID customerId, String line, PolicyStatus status) {
        Policy p = new Policy();
        p.setPolicyId(id);
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(customerId);
        p.setProductId("HEALTH_BASIC");
        p.setLine(line);
        p.setStatus(status);
        p.setPolicyEffectiveDate(OffsetDateTime.now().minusDays(30));
        p.setPolicyExpirationDate(OffsetDateTime.now().plusDays(335));
        p.setRenewalNumber(0);
        p.setRenewal(false);
        p.setYearsSinceFirstPolicy(0);
        p.setPolicyCountPrior(0);
        p.setFinalPremiumVnd(2_980_000L);
        p.setCreatedAt(OffsetDateTime.now());
        return p;
    }

    @Test
    void adminListPoliciesPagedReturnsPageResponse() {
        PolicyRepository repo = mock(PolicyRepository.class);
        Policy p1 = policy(UUID.randomUUID(), UUID.randomUUID(), "health", PolicyStatus.active);
        Policy p2 = policy(UUID.randomUUID(), UUID.randomUUID(), "motorbike", PolicyStatus.active);
        when(repo.findFiltered(eq(null), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 20), 2));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo,
                mock(dpp.order.repository.ExposureSegmentRepository.class),
                mock(dpp.order.repository.PolicyDocumentRepository.class),
                mock(dpp.order.repository.EndorsementRequestRepository.class),
                mock(dpp.order.client.PricingClient.class),
                mock(dpp.order.client.BillingClient.class),
                mock(dpp.common.outbox.OutboxPublisher.class));

        PageResponse<PolicyResponse> result = svc.adminListPoliciesPaged(null, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(2, result.getContent().size());
        assertEquals(0, result.getPage());
        assertEquals(2, result.getTotalElements());
        assertEquals(1, result.getTotalPages());
    }

    @Test
    void adminListPoliciesPagedWithStatusFilter() {
        PolicyRepository repo = mock(PolicyRepository.class);
        Policy p = policy(UUID.randomUUID(), UUID.randomUUID(), "health", PolicyStatus.cancelled);
        when(repo.findFiltered(eq(PolicyStatus.cancelled), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 20), 1));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo,
                mock(dpp.order.repository.ExposureSegmentRepository.class),
                mock(dpp.order.repository.PolicyDocumentRepository.class),
                mock(dpp.order.repository.EndorsementRequestRepository.class),
                mock(dpp.order.client.PricingClient.class),
                mock(dpp.order.client.BillingClient.class),
                mock(dpp.common.outbox.OutboxPublisher.class));

        PageResponse<PolicyResponse> result = svc.adminListPoliciesPaged(PolicyStatus.cancelled, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(1, result.getContent().size());
        assertEquals(PolicyStatus.cancelled, result.getContent().get(0).getStatus());
    }

    @Test
    void adminListPoliciesPagedWithLineFilter() {
        PolicyRepository repo = mock(PolicyRepository.class);
        Policy p = policy(UUID.randomUUID(), UUID.randomUUID(), "motorbike", PolicyStatus.active);
        when(repo.findFiltered(eq(null), eq(null), eq("motorbike"), any()))
                .thenReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 20), 1));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo,
                mock(dpp.order.repository.ExposureSegmentRepository.class),
                mock(dpp.order.repository.PolicyDocumentRepository.class),
                mock(dpp.order.repository.EndorsementRequestRepository.class),
                mock(dpp.order.client.PricingClient.class),
                mock(dpp.order.client.BillingClient.class),
                mock(dpp.common.outbox.OutboxPublisher.class));

        PageResponse<PolicyResponse> result = svc.adminListPoliciesPaged(null, null, "motorbike",
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(1, result.getContent().size());
        assertEquals("motorbike", result.getContent().get(0).getLine());
    }

    @Test
    void adminListPoliciesPagedWithCustomerIdFilter() {
        PolicyRepository repo = mock(PolicyRepository.class);
        UUID customerId = UUID.randomUUID();
        Policy p = policy(UUID.randomUUID(), customerId, "health", PolicyStatus.active);
        when(repo.findFiltered(eq(null), eq(customerId), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 20), 1));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo,
                mock(dpp.order.repository.ExposureSegmentRepository.class),
                mock(dpp.order.repository.PolicyDocumentRepository.class),
                mock(dpp.order.repository.EndorsementRequestRepository.class),
                mock(dpp.order.client.PricingClient.class),
                mock(dpp.order.client.BillingClient.class),
                mock(dpp.common.outbox.OutboxPublisher.class));

        PageResponse<PolicyResponse> result = svc.adminListPoliciesPaged(null, customerId, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(1, result.getContent().size());
        assertEquals(customerId, result.getContent().get(0).getCustomerId());
    }

    @Test
    void adminListPoliciesPagedEmptyResult() {
        PolicyRepository repo = mock(PolicyRepository.class);
        when(repo.findFiltered(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PolicyLifecycleService svc = new PolicyLifecycleService(repo,
                mock(dpp.order.repository.ExposureSegmentRepository.class),
                mock(dpp.order.repository.PolicyDocumentRepository.class),
                mock(dpp.order.repository.EndorsementRequestRepository.class),
                mock(dpp.order.client.PricingClient.class),
                mock(dpp.order.client.BillingClient.class),
                mock(dpp.common.outbox.OutboxPublisher.class));

        PageResponse<PolicyResponse> result = svc.adminListPoliciesPaged(null, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertTrue(result.getContent().isEmpty());
        assertEquals(0, result.getTotalElements());
    }

    @Test
    void toResponseIncludesLine() {
        Policy p = policy(UUID.randomUUID(), UUID.randomUUID(), "travel", PolicyStatus.active);
        PolicyLifecycleService svc = new PolicyLifecycleService(mock(PolicyRepository.class),
                mock(dpp.order.repository.ExposureSegmentRepository.class),
                mock(dpp.order.repository.PolicyDocumentRepository.class),
                mock(dpp.order.repository.EndorsementRequestRepository.class),
                mock(dpp.order.client.PricingClient.class),
                mock(dpp.order.client.BillingClient.class),
                mock(dpp.common.outbox.OutboxPublisher.class));

        PolicyResponse resp = svc.toResponse(p);
        assertEquals("travel", resp.getLine());
    }
}
