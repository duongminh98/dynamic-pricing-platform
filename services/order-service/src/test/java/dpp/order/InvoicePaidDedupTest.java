package dpp.order;

import dpp.common.outbox.OutboxPublisher;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.ProcessedEvent;
import dpp.order.repository.ExposureSegmentRepository;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyDocumentRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.repository.ProcessedEventRepository;
import dpp.order.service.PolicyIssuanceService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** InvoicePaid consumer idempotency on X-Event-Id (R6.6). */
class InvoicePaidDedupTest {

    private OrderEntity pendingPaymentOrder() {
        OrderEntity o = new OrderEntity();
        o.setOrderId(UUID.randomUUID());
        o.setCustomerId(UUID.randomUUID());
        o.setProductId("MOTOR_BASIC");
        o.setFinalPremiumVnd(1_000_000L);
        o.setStatus(OrderStatus.PENDING_PAYMENT);
        o.setCreatedAt(OffsetDateTime.now());
        return o;
    }

    @Test
    void duplicateEventIdIsNoOp() {
        OrderEntity order = pendingPaymentOrder();
        OrderRepository orderRepo = mock(OrderRepository.class);
        ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
        when(processed.existsById("evt-1")).thenReturn(true);

        PolicyIssuanceService svc = new PolicyIssuanceService(orderRepo, mock(PolicyRepository.class),
                mock(ExposureSegmentRepository.class), mock(PolicyDocumentRepository.class),
                processed, mock(OutboxPublisher.class));

        svc.issuePolicy("evt-1", order.getOrderId(), null);

        // Already processed -> must not even load the order or issue a policy.
        verify(orderRepo, never()).findById(any());
    }

    @Test
    void firstEventIdRecordsProcessedEventAndIssues() {
        OrderEntity order = pendingPaymentOrder();
        OrderRepository orderRepo = mock(OrderRepository.class);
        when(orderRepo.findById(order.getOrderId())).thenReturn(java.util.Optional.of(order));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        when(policyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ExposureSegmentRepository segRepo = mock(ExposureSegmentRepository.class);
        when(segRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PolicyDocumentRepository docRepo = mock(PolicyDocumentRepository.class);
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
        when(processed.existsById("evt-2")).thenReturn(false);

        PolicyIssuanceService svc = new PolicyIssuanceService(orderRepo, policyRepo, segRepo, docRepo,
                processed, mock(OutboxPublisher.class));

        svc.issuePolicy("evt-2", order.getOrderId(), null);

        verify(processed, times(1)).save(any(ProcessedEvent.class));
        verify(policyRepo, times(1)).save(any());
    }
}
