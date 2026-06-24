package dpp.order;

import dpp.common.outbox.OutboxPublisher;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.repository.ProcessedEventRepository;
import dpp.order.service.PolicyIssuanceService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 8")
class ExposureInvariantPropertyTest {

    private OrderEntity pendingPaymentOrder(long premium) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(UUID.randomUUID());
        order.setCustomerId(UUID.randomUUID());
        order.setProductId("motor-001");
        order.setFinalPremiumVnd(premium);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        return order;
    }

    private PolicyIssuanceService newService(OrderRepository orderRepo, PolicyRepository policyRepo,
                                              ExposureSegmentRepository segRepo) {
        return new PolicyIssuanceService(orderRepo, policyRepo, segRepo,
                mock(PolicyDocumentRepository.class), mock(ProcessedEventRepository.class), mock(OutboxPublisher.class));
    }

    @Property(tries = 100)
    void issuanceCreatesSegmentWithSeqZeroAndPositiveExposure(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        UUID orderId = UUID.randomUUID();
        OrderRepository orderRepo = mock(OrderRepository.class);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(pendingPaymentOrder(premium)));
        when(orderRepo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRepository policyRepo = mock(PolicyRepository.class);
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyIssuanceService svc = newService(orderRepo, policyRepo, segRepo);
        svc.issuePolicy(null, orderId, null);

        ArgumentCaptor<ExposureSegment> captor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo, times(1)).save(captor.capture());
        ExposureSegment seg = captor.getValue();
        assertEquals(0, seg.getExposureSegmentSeq());
        assertTrue(seg.getEarnedExposureYears() > 0);
    }

    @Property(tries = 100)
    void issuanceSkipsNonPendingPaymentOrder(@ForAll int seed) {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingPaymentOrder(500_000L);
        order.setOrderId(orderId);
        order.setStatus(OrderStatus.COMPLETED);

        OrderRepository orderRepo = mock(OrderRepository.class);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));

        PolicyRepository policyRepo = mock(PolicyRepository.class);
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);

        PolicyIssuanceService svc = newService(orderRepo, policyRepo, segRepo);
        svc.issuePolicy(null, orderId, null);

        verify(policyRepo, never()).save(any());
        verify(segRepo, never()).save(any());
    }

    @Test
    void property8_sanity() {
        UUID orderId = UUID.randomUUID();
        OrderRepository orderRepo = mock(OrderRepository.class);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(pendingPaymentOrder(500_000L)));
        when(orderRepo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRepository policyRepo = mock(PolicyRepository.class);
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyIssuanceService svc = newService(orderRepo, policyRepo, segRepo);
        svc.issuePolicy(null, orderId, null);
        ArgumentCaptor<ExposureSegment> captor = ArgumentCaptor.forClass(ExposureSegment.class);
        verify(segRepo).save(captor.capture());
        assertEquals(0, captor.getValue().getExposureSegmentSeq());
    }
}
