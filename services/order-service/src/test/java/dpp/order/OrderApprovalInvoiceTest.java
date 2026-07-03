package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.order.dto.OrderResponse;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.ReviewDecision;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.repository.QuoteSnapshotRepository;
import dpp.order.service.OrderApprovalTransactionService;
import dpp.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderApprovalInvoiceTest {

    private OrderEntity pendingReviewOrder() {
        OrderEntity order = new OrderEntity();
        order.setOrderId(UUID.randomUUID());
        order.setQuoteId(UUID.randomUUID());
        order.setCustomerId(UUID.randomUUID());
        order.setProductId("HEALTH_BASIC");
        order.setLine("health");
        order.setFinalPremiumVnd(500_000L);
        order.setStatus(OrderStatus.PENDING_REVIEW);
        return order;
    }

    private OrderService newService(OrderRepository repo, BillingClient billing, OutboxPublisher outbox) {
        PricingClient pricing = mock(PricingClient.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        OrderApprovalTransactionService approvalTx = new OrderApprovalTransactionService(repo, outbox);
        return new OrderService(repo, pricing, mock(QuoteSnapshotRepository.class), billing, outbox, policyRepo, approvalTx);
    }

    @Test
    void approveSetsPendingPaymentWithNullInvoiceId() {
        OrderRepository repo = mock(OrderRepository.class);
        BillingClient billing = mock(BillingClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder();
        order.setOrderId(orderId);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderService svc = newService(repo, billing, outbox);
        OrderResponse resp = svc.approve(orderId, "admin-001");

        assertEquals(OrderStatus.PENDING_PAYMENT, resp.getStatus());
        assertEquals(ReviewDecision.APPROVE, resp.getReviewDecision());
        assertNull(resp.getInvoiceId(), "invoice_id must be null — billing creates it asynchronously");
        verify(billing, never()).createInvoice(any(), any(), anyLong());
    }

    @Test
    void approveEnqueuesOrderApprovedWithoutInvoiceId() {
        OrderRepository repo = mock(OrderRepository.class);
        BillingClient billing = mock(BillingClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder();
        order.setOrderId(orderId);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderService svc = newService(repo, billing, outbox);
        svc.approve(orderId, "admin-001");

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox, atLeast(1)).enqueue(typeCaptor.capture(), payloadCaptor.capture());

        boolean foundApproved = false;
        for (int i = 0; i < typeCaptor.getAllValues().size(); i++) {
            if ("OrderApproved".equals(typeCaptor.getAllValues().get(i))) {
                foundApproved = true;
                String payload = payloadCaptor.getAllValues().get(i);
                assertFalse(payload.contains("invoice_id"),
                        "OrderApproved payload must NOT contain invoice_id — billing creates it asynchronously");
                assertTrue(payload.contains("\"status\":\"PENDING_PAYMENT\""), "Payload must contain status");
                assertTrue(payload.contains("\"line\":\"health\""), "Payload must contain line");
                assertTrue(payload.contains("\"final_premium_vnd\":500000"), "Payload must contain final_premium_vnd");
            }
        }
        assertTrue(foundApproved, "OrderApproved event must be enqueued");
    }

    @Test
    void approveRejectsNonPendingReview() {
        OrderRepository repo = mock(OrderRepository.class);
        BillingClient billing = mock(BillingClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder();
        order.setOrderId(orderId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));

        OrderService svc = newService(repo, billing, outbox);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(orderId, "admin-001"));
        assertEquals(ErrorCode.ORDER_NOT_APPROVED, ex.getErrorCode());
        verify(billing, never()).createInvoice(any(), any(), anyLong());
        verify(outbox, never()).enqueue(anyString(), anyString());
    }

    @Test
    void approveDoesNotCallBillingCreateInvoice() {
        OrderRepository repo = mock(OrderRepository.class);
        BillingClient billing = mock(BillingClient.class);
        OutboxPublisher outbox = mock(OutboxPublisher.class);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder();
        order.setOrderId(orderId);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderService svc = newService(repo, billing, outbox);
        svc.approve(orderId, "admin-001");

        verify(billing, never()).createInvoice(any(), any(), anyLong());
    }
}

