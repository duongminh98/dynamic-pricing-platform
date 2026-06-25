package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.order.controller.InternalOwnerController;
import dpp.order.dto.ExposureSegmentResponse;
import dpp.order.dto.OwnerResponse;
import dpp.order.dto.PolicyResponse;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalOwnerControllerTest {

    private OrderEntity order(UUID id, UUID customerId) {
        OrderEntity o = new OrderEntity();
        o.setOrderId(id);
        o.setQuoteId(UUID.randomUUID());
        o.setCustomerId(customerId);
        o.setProductId("motor-basic");
        o.setFinalPremiumVnd(1_000_000L);
        o.setStatus(OrderStatus.PENDING_PAYMENT);
        o.setCreatedAt(OffsetDateTime.now());
        return o;
    }

    private Policy policy(UUID id, UUID customerId) {
        Policy p = new Policy();
        p.setPolicyId(id);
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

    @Test
    void getOrderOwnerReturnsCustomerId() {
        OrderRepository orderRepo = mock(OrderRepository.class);
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order(orderId, customerId)));

        InternalOwnerController controller = new InternalOwnerController(
                orderRepo, mock(PolicyRepository.class), mock(ExposureSegmentRepository.class),
                mock(PolicyLifecycleService.class));
        OwnerResponse resp = controller.getOrderOwner(orderId);

        assertEquals(customerId, resp.getCustomerId());
    }

    @Test
    void getOrderOwnerRejectsUnknownOrder() {
        OrderRepository orderRepo = mock(OrderRepository.class);
        UUID orderId = UUID.randomUUID();
        when(orderRepo.findById(orderId)).thenReturn(Optional.empty());

        InternalOwnerController controller = new InternalOwnerController(
                orderRepo, mock(PolicyRepository.class), mock(ExposureSegmentRepository.class),
                mock(PolicyLifecycleService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getOrderOwner(orderId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getPolicyOwnerReturnsCustomerId() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(policy(policyId, customerId)));

        InternalOwnerController controller = new InternalOwnerController(
                mock(OrderRepository.class), policyRepo, mock(ExposureSegmentRepository.class),
                mock(PolicyLifecycleService.class));
        OwnerResponse resp = controller.getPolicyOwner(policyId);

        assertEquals(customerId, resp.getCustomerId());
    }

    @Test
    void getPolicyOwnerRejectsUnknownPolicy() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.empty());

        InternalOwnerController controller = new InternalOwnerController(
                mock(OrderRepository.class), policyRepo, mock(ExposureSegmentRepository.class),
                mock(PolicyLifecycleService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getPolicyOwner(policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getPolicyReturnsPolicyResponse() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        PolicyLifecycleService lifecycleService = mock(PolicyLifecycleService.class);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Policy p = policy(policyId, customerId);
        when(policyRepo.findById(policyId)).thenReturn(Optional.of(p));

        PolicyResponse mockResp = new PolicyResponse();
        mockResp.setPolicyId(policyId);
        when(lifecycleService.toResponse(p)).thenReturn(mockResp);

        InternalOwnerController controller = new InternalOwnerController(
                mock(OrderRepository.class), policyRepo, mock(ExposureSegmentRepository.class),
                lifecycleService);
        PolicyResponse resp = controller.getPolicy(policyId);

        assertEquals(policyId, resp.getPolicyId());
    }

    @Test
    void getPolicyRejectsUnknownPolicy() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        UUID policyId = UUID.randomUUID();
        when(policyRepo.findById(policyId)).thenReturn(Optional.empty());

        InternalOwnerController controller = new InternalOwnerController(
                mock(OrderRepository.class), policyRepo, mock(ExposureSegmentRepository.class),
                mock(PolicyLifecycleService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getPolicy(policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void exposureSegmentsReturnsSegments() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        UUID policyId = UUID.randomUUID();
        when(policyRepo.existsById(policyId)).thenReturn(true);

        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policyId);
        seg.setExposureSegmentSeq(0);
        seg.setSegmentStart(OffsetDateTime.now().minusDays(30));
        seg.setSegmentEnd(OffsetDateTime.now().plusDays(335));
        seg.setEarnedExposureYears(0.0);
        seg.setCoverageAmountVnd(100_000_000L);
        seg.setDeductibleVnd(0L);
        seg.setRiskSnapshot("{}");
        when(segRepo.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId)).thenReturn(List.of(seg));

        InternalOwnerController controller = new InternalOwnerController(
                mock(OrderRepository.class), policyRepo, segRepo, mock(PolicyLifecycleService.class));
        List<ExposureSegmentResponse> result = controller.exposureSegments(policyId);

        assertEquals(1, result.size());
        assertEquals(policyId, result.get(0).getPolicyId());
        assertEquals(100_000_000L, result.get(0).getCoverageAmountVnd());
    }

    @Test
    void exposureSegmentsRejectsUnknownPolicy() {
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        UUID policyId = UUID.randomUUID();
        when(policyRepo.existsById(policyId)).thenReturn(false);

        InternalOwnerController controller = new InternalOwnerController(
                mock(OrderRepository.class), policyRepo, mock(ExposureSegmentRepository.class),
                mock(PolicyLifecycleService.class));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.exposureSegments(policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }
}
