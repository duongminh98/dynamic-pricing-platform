package dpp.order;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
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

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("Feature: dynamic-pricing-platform, Property 14")
class OrderCreationPropertyTest {

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
    void onlyValidUnusedQuoteCreatesPendingReviewOrder(
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

        assertEquals(OrderStatus.PENDING_REVIEW, resp.getStatus());
        assertEquals(premium, resp.getFinalPremiumVnd());
        assertEquals(quoteId, resp.getQuoteId());
        verify(repo, times(1)).save(any(OrderEntity.class));
    }

    @Property(tries = 100)
    void alreadyUsedQuoteRejected(@ForAll int seed) {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID quoteId = UUID.randomUUID();
        OrderEntity existing = new OrderEntity();
        existing.setQuoteId(quoteId);
        existing.setStatus(OrderStatus.PENDING_REVIEW);
        when(repo.findByQuoteId(quoteId)).thenReturn(Optional.of(existing));

        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.createOrder("subject-abc", req));
        assertEquals(ErrorCode.QUOTE_ALREADY_USED, ex.getErrorCode());
        verify(repo, never()).save(any());
    }

    @Property(tries = 100)
    void expiredQuoteRejected(@ForAll int seed) {
        OrderRepository repo = mock(OrderRepository.class);
        PricingClient pricing = mock(PricingClient.class);
        BillingClient billing = mock(BillingClient.class);
        OrderService svc = newService(repo, pricing, billing);

        UUID quoteId = UUID.randomUUID();
        when(repo.findByQuoteId(quoteId)).thenReturn(Optional.empty());
        Map<String, Object> quote = new HashMap<>();
        quote.put("expires_at", OffsetDateTime.now().minusDays(1).toString());
        quote.put("final_premium_vnd", 500_000L);
        quote.put("product_id", "motor-001");
        when(pricing.getQuote(quoteId)).thenReturn(quote);

        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        ServiceException ex = assertThrows(ServiceException.class, () -> svc.createOrder("subject-abc", req));
        assertEquals(ErrorCode.QUOTE_EXPIRED, ex.getErrorCode());
        verify(repo, never()).save(any());
    }

    @Test
    void property14_sanity() {
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
        assertEquals(OrderStatus.PENDING_REVIEW, resp.getStatus());
    }
}
