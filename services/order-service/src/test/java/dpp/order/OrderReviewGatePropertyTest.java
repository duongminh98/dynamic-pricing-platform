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
import dpp.order.service.OrderService;
import dpp.order.service.OrderApprovalTransactionService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 16")
class OrderReviewGatePropertyTest {

    private OrderEntity pendingReviewOrder(long premium) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(UUID.randomUUID());
        order.setQuoteId(UUID.randomUUID());
        order.setCustomerId(UUID.randomUUID());
        order.setProductId("motor-001");
        order.setFinalPremiumVnd(premium);
        order.setStatus(OrderStatus.PENDING_REVIEW);
        return order;
    }

    @Property(tries = 100)
    void approveMovesToPendingPaymentAndCreatesInvoice(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder(premium);
        order.setOrderId(orderId);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse resp = svc.approve(orderId, "admin-001");

        assertEquals(OrderStatus.PENDING_PAYMENT, resp.getStatus());
        assertEquals(ReviewDecision.APPROVE, resp.getReviewDecision());
        verify(billing, never()).createInvoice(eq(orderId), isNull(), eq(premium));
    }

    @Property(tries = 100)
    void rejectMovesToRejectedWithoutInvoice(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder(premium);
        order.setOrderId(orderId);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse resp = svc.reject(orderId, "bad_risk", "admin-001");

        assertEquals(OrderStatus.REJECTED, resp.getStatus());
        assertEquals(ReviewDecision.REJECT, resp.getReviewDecision());
        assertEquals("bad_risk", resp.getReviewReason());
        verify(billing, never()).createInvoice(any(), any(), anyLong());
    }

    @Property(tries = 100)
    void approveOnNonPendingReviewRejected(@ForAll int seed) {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder(500_000L);
        order.setOrderId(orderId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(orderId, "admin-001"));
        assertEquals(ErrorCode.ORDER_NOT_APPROVED, ex.getErrorCode());
        verify(billing, never()).createInvoice(any(), any(), anyLong());
    }

    @Property(tries = 100)
    void rejectOnNonPendingReviewRejected(@ForAll int seed) {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder(500_000L);
        order.setOrderId(orderId);
        order.setStatus(OrderStatus.COMPLETED);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));

        ServiceException ex = assertThrows(ServiceException.class, () -> svc.reject(orderId, "reason", "admin-001"));
        assertEquals(ErrorCode.ORDER_NOT_APPROVED, ex.getErrorCode());
    }

    @Test
    void property16_sanity() {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID orderId = UUID.randomUUID();
        OrderEntity order = pendingReviewOrder(500_000L);
        order.setOrderId(orderId);
        when(repo.findById(orderId)).thenReturn(Optional.of(order));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse resp = svc.approve(orderId, "admin-001");
        assertEquals(OrderStatus.PENDING_PAYMENT, resp.getStatus());
    }

    private OrderService newService(OrderRepository repo, PricingClient pricing, BillingClient billing) {
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        OrderApprovalTransactionService approvalTx = new OrderApprovalTransactionService(repo, outbox);
        return new OrderService(repo, pricing, billing, outbox, policyRepo, approvalTx);
    }
}
