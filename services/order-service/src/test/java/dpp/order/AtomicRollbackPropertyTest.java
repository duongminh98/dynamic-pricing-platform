package dpp.order;

import dpp.common.outbox.OutboxPublisher;
import dpp.order.entity.ExposureSegment;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.repository.ProcessedEventRepository;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyIssuanceService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 25")
class AtomicRollbackPropertyTest {

    private OrderEntity pendingPaymentOrder(long premium) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(UUID.randomUUID());
        order.setCustomerId(UUID.randomUUID());
        order.setProductId("motor-001");
        order.setFinalPremiumVnd(premium);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        return order;
    }

    @Property(tries = 100)
    void outboxFailurePropagatesAndRollsBack(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingPaymentOrder(premium);
        order.setOrderId(orderId);

        OrderRepository orderRepo = mock(OrderRepository.class);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRepository policyRepo = mock(PolicyRepository.class);
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(outbox.enqueue(anyString(), anyString()))
                .thenThrow(new RuntimeException("MQ down"));

        PolicyIssuanceService svc = new PolicyIssuanceService(orderRepo, policyRepo, segRepo, docRepo, mock(ProcessedEventRepository.class), outbox);
        assertThrows(RuntimeException.class, () -> svc.issuePolicy(null, orderId, null));
    }

    @Property(tries = 100)
    void outboxEnqueuedAfterBusinessWrites(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingPaymentOrder(premium);
        order.setOrderId(orderId);

        OrderRepository orderRepo = mock(OrderRepository.class);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRepository policyRepo = mock(PolicyRepository.class);
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(outbox.enqueue(anyString(), anyString())).thenReturn(null);

        PolicyIssuanceService svc = new PolicyIssuanceService(orderRepo, policyRepo, segRepo, docRepo, mock(ProcessedEventRepository.class), outbox);
        svc.issuePolicy(null, orderId, null);

        org.mockito.InOrder inOrder = inOrder(policyRepo, segRepo, docRepo, outbox);
        inOrder.verify(policyRepo).save(any());
        inOrder.verify(segRepo).save(any());
        inOrder.verify(docRepo).save(any());
        inOrder.verify(outbox).enqueue(anyString(), anyString());
    }

    @Test
    void property25_sanity() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingPaymentOrder(500_000L);
        order.setOrderId(orderId);

        OrderRepository orderRepo = mock(OrderRepository.class);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.save(any(ExposureSegment.class))).thenAnswer(inv -> inv.getArgument(0));
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(outbox.enqueue(anyString(), anyString())).thenThrow(new RuntimeException("down"));

        PolicyIssuanceService svc = new PolicyIssuanceService(orderRepo, policyRepo, segRepo, docRepo, mock(ProcessedEventRepository.class), outbox);
        assertThrows(RuntimeException.class, () -> svc.issuePolicy(null, orderId, null));
    }
}
