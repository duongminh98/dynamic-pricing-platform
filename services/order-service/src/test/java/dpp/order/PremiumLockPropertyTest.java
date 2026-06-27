package dpp.order;

import dpp.common.outbox.OutboxPublisher;
import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.order.dto.CreateOrderRequest;
import dpp.order.dto.OrderResponse;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.OrderService;
import dpp.order.service.OrderApprovalTransactionService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 15")
class PremiumLockPropertyTest {

    private OrderService newService(OrderRepository repo, PricingClient pricing, BillingClient billing) {
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        PolicyRepository policyRepo = mock(PolicyRepository.class);
        when(policyRepo.existsActivePolicy(any(), any())).thenReturn(false);
        OrderApprovalTransactionService approvalTx = new OrderApprovalTransactionService(repo, outbox);
        return new OrderService(repo, pricing, billing, outbox, policyRepo, approvalTx);
    }

    private Map<String, Object> validQuote(long premium) {
        Map<String, Object> quote = new HashMap<>();
        quote.put("expires_at", OffsetDateTime.now().plusDays(7).toString());
        quote.put("final_premium_vnd", premium);
        quote.put("product_id", "motor-001");
        return quote;
    }

    @Property(tries = 100)
    void orderPremiumLockedFromQuote(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID quoteId = UUID.randomUUID();
        when(repo.findByQuoteId(quoteId)).thenReturn(Optional.empty());
        when(pricing.getQuote(quoteId)).thenReturn(validQuote(premium));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        OrderResponse resp = svc.createOrder("subject-abc", req);

        assertEquals(premium, resp.getFinalPremiumVnd());
    }

    @Property(tries = 100)
    void invoiceAmountEqualsLockedPremium(
            @ForAll @LongRange(min = 1, max = 100_000_000) long premium) {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID quoteId = UUID.randomUUID();
        when(repo.findByQuoteId(quoteId)).thenReturn(Optional.empty());
        when(pricing.getQuote(quoteId)).thenReturn(validQuote(premium));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        svc.createOrder("subject-abc", req);

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(repo, atLeast(1)).save(captor.capture());
        OrderEntity saved = captor.getValue();

        OrderEntity approveOrder = new OrderEntity();
        approveOrder.setOrderId(saved.getOrderId());
        approveOrder.setCustomerId(saved.getCustomerId());
        approveOrder.setProductId(saved.getProductId());
        approveOrder.setFinalPremiumVnd(saved.getFinalPremiumVnd());
        approveOrder.setStatus(OrderStatus.PENDING_REVIEW);

        when(repo.findById(saved.getOrderId())).thenReturn(Optional.of(approveOrder));
        svc.approve(saved.getOrderId(), "admin-001");

        verify(billing).createInvoice(eq(saved.getOrderId()), isNull(), eq(premium));
    }

    @Test
    void property15_sanity() {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID quoteId = UUID.randomUUID();
        when(repo.findByQuoteId(quoteId)).thenReturn(Optional.empty());
        when(pricing.getQuote(quoteId)).thenReturn(validQuote(1_000_000L));
        when(repo.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        OrderResponse resp = svc.createOrder("subject-abc", req);
        assertEquals(1_000_000L, resp.getFinalPremiumVnd());
    }
}
