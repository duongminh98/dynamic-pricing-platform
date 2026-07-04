package dpp.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.common.security.CustomerId;
import dpp.order.client.BillingClient;
import dpp.order.dto.CreateOrderRequest;
import dpp.order.dto.OrderResponse;
import dpp.order.dto.ReviewQueueItem;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.QuoteSnapshot;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyRepository;
import dpp.order.repository.QuoteSnapshotRepository;
import dpp.order.service.OrderApprovalTransactionService;
import dpp.order.service.OrderService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OrderService} read/create/reject flows using mocked
 * repositories and clients. Exercises the response mappers, asset-key
 * extraction, risk-profile parsing, and event enqueue helpers — no DB, no broker.
 */
@Tag("Feature: dynamic-pricing-platform")
class OrderServiceCoverageTest {

    private final OrderRepository orderRepo = mock(OrderRepository.class);
    private final QuoteSnapshotRepository quoteRepo = mock(QuoteSnapshotRepository.class);
    private final BillingClient billingClient = mock(BillingClient.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final PolicyRepository policyRepo = mock(PolicyRepository.class);
    private final OrderApprovalTransactionService approvalTx = mock(OrderApprovalTransactionService.class);

    private OrderService service() {
        return new OrderService(orderRepo, quoteRepo, billingClient, outbox, policyRepo, approvalTx);
    }

    private QuoteSnapshot quote(UUID quoteId, String line, String profileJson) {
        QuoteSnapshot q = new QuoteSnapshot();
        q.setQuoteId(quoteId);
        q.setCustomerId(UUID.randomUUID());
        q.setProductId("HEALTH_BASIC");
        q.setLine(line);
        q.setCoverageAmountVnd(100_000_000L);
        q.setDeductibleVnd(0L);
        q.setFinalPremiumVnd(298_000L);
        q.setProfile(profileJson);
        q.setExpiresAt(OffsetDateTime.now().plusDays(5));
        q.setCreatedAt(OffsetDateTime.now());
        return q;
    }

    @Test
    void createOrderHappyPathEnqueuesSubmittedAndReturnsResponse() throws Exception {
        UUID quoteId = UUID.randomUUID();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);

        when(orderRepo.findByQuoteId(quoteId)).thenReturn(Optional.empty());
        when(quoteRepo.findById(quoteId)).thenReturn(Optional.of(quote(quoteId, "accident", "{\"age\":30}")));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse resp = service().createOrder("cust-subject", req);

        assertEquals(quoteId, resp.getQuoteId());
        assertEquals("HEALTH_BASIC", resp.getProductId());
        assertEquals(OrderStatus.PENDING_REVIEW, resp.getStatus());
        verify(outbox).enqueue(eq("OrderSubmitted"), anyString());
    }

    @Test
    void createOrderRejectsAlreadyUsedQuote() {
        UUID quoteId = UUID.randomUUID();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        when(orderRepo.findByQuoteId(quoteId)).thenReturn(Optional.of(new OrderEntity()));

        assertThrows(ServiceException.class, () -> service().createOrder("s", req));
    }

    @Test
    void createOrderRejectsExpiredQuote() {
        UUID quoteId = UUID.randomUUID();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        QuoteSnapshot expired = quote(quoteId, "accident", "{}");
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(orderRepo.findByQuoteId(quoteId)).thenReturn(Optional.empty());
        when(quoteRepo.findById(quoteId)).thenReturn(Optional.of(expired));

        assertThrows(ServiceException.class, () -> service().createOrder("s", req));
    }

    @Test
    void createOrderRejectsDuplicateActivePolicyForIndemnityLine() {
        UUID quoteId = UUID.randomUUID();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        when(orderRepo.findByQuoteId(quoteId)).thenReturn(Optional.empty());
        when(quoteRepo.findById(quoteId)).thenReturn(Optional.of(
                quote(quoteId, "car", "{\"vehicle_plate\":\"51F-12345\"}")));
        when(policyRepo.existsActivePolicy(any(), eq("51F-12345"))).thenReturn(true);

        assertThrows(ServiceException.class, () -> service().createOrder("s", req));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void createOrderTravelAssetKeyIsCompositeAndAllowedWhenNoActivePolicy() throws Exception {
        UUID quoteId = UUID.randomUUID();
        CreateOrderRequest req = new CreateOrderRequest();
        req.setQuoteId(quoteId);
        String profile = "{\"destination_country\":\"JP\",\"trip_start_date\":\"2026-08-01\",\"trip_end_date\":\"2026-08-10\"}";
        when(orderRepo.findByQuoteId(quoteId)).thenReturn(Optional.empty());
        when(quoteRepo.findById(quoteId)).thenReturn(Optional.of(quote(quoteId, "travel", profile)));
        when(policyRepo.existsActivePolicy(any(), eq("JP|2026-08-01|2026-08-10"))).thenReturn(false);
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse resp = service().createOrder("s", req);
        assertEquals(OrderStatus.PENDING_REVIEW, resp.getStatus());
        verify(policyRepo).existsActivePolicy(any(), eq("JP|2026-08-01|2026-08-10"));
    }

    @Test
    void myOrdersMapsAllForCustomer() {
        UUID customerId = CustomerId.fromSubject("cust-subject");
        OrderEntity o1 = orderEntity(customerId, OrderStatus.PENDING_REVIEW);
        OrderEntity o2 = orderEntity(customerId, OrderStatus.COMPLETED);
        when(orderRepo.findByCustomerIdOrderByCreatedAtDesc(customerId)).thenReturn(List.of(o1, o2));

        List<OrderResponse> resp = service().myOrders("cust-subject");
        assertEquals(2, resp.size());
    }

    @Test
    void getMyOrderForbidsOtherCustomer() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = orderEntity(UUID.randomUUID(), OrderStatus.PENDING_REVIEW);
        order.setOrderId(orderId);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(ServiceException.class, () -> service().getMyOrder("someone-else", orderId));
    }

    @Test
    void getMyOrderReturnsOwnOrder() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = CustomerId.fromSubject("owner");
        OrderEntity order = orderEntity(customerId, OrderStatus.PENDING_REVIEW);
        order.setOrderId(orderId);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));

        assertEquals(orderId, service().getMyOrder("owner", orderId).getOrderId());
    }

    @Test
    void getOrderThrowsWhenMissing() {
        UUID orderId = UUID.randomUUID();
        when(orderRepo.findById(orderId)).thenReturn(Optional.empty());
        assertThrows(ServiceException.class, () -> service().getOrder(orderId));
    }

    @Test
    void reviewQueueMapsToQueueItemsWithLineFilter() {
        OrderEntity order = orderEntity(UUID.randomUUID(), OrderStatus.PENDING_REVIEW);
        Page<OrderEntity> page = new PageImpl<>(List.of(order));
        when(orderRepo.findByStatusAndLine(eq(OrderStatus.PENDING_REVIEW), eq("health"), any())).thenReturn(page);

        Page<ReviewQueueItem> result = service().reviewQueue(0, 20, "health");
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void reviewQueueWithoutLineFilter() {
        when(orderRepo.findByStatus(eq(OrderStatus.PENDING_REVIEW), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        assertNotNull(service().reviewQueue(0, 20, null));
    }

    @Test
    void adminListOrdersMapsResponses() {
        OrderEntity order = orderEntity(UUID.randomUUID(), OrderStatus.COMPLETED);
        when(orderRepo.findFiltered(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(order)));

        Page<OrderResponse> result = service().adminListOrders(OrderStatus.COMPLETED, null, null, 0, 20);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void rejectSetsStatusAndEnqueuesEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = orderEntity(UUID.randomUUID(), OrderStatus.PENDING_REVIEW);
        order.setOrderId(orderId);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse resp = service().reject(orderId, "high risk", "admin-1");

        assertEquals(OrderStatus.REJECTED, resp.getStatus());
        assertEquals("high risk", resp.getReviewReason());
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(type.capture(), anyString());
        assertEquals("OrderRejected", type.getValue());
    }

    @Test
    void rejectRejectsNonPendingOrder() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = orderEntity(UUID.randomUUID(), OrderStatus.COMPLETED);
        order.setOrderId(orderId);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(ServiceException.class, () -> service().reject(orderId, "x", "admin"));
    }

    @Test
    void approveDelegatesToTransactionService() {
        UUID orderId = UUID.randomUUID();
        OrderEntity order = orderEntity(UUID.randomUUID(), OrderStatus.PENDING_REVIEW);
        order.setOrderId(orderId);
        OrderEntity approved = orderEntity(order.getCustomerId(), OrderStatus.PENDING_PAYMENT);
        approved.setOrderId(orderId);
        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
        when(approvalTx.approveWithInvoice(orderId, "admin-1", null)).thenReturn(approved);

        OrderResponse resp = service().approve(orderId, "admin-1");
        assertEquals(OrderStatus.PENDING_PAYMENT, resp.getStatus());
        verify(approvalTx).approveWithInvoice(orderId, "admin-1", null);
    }

    private OrderEntity orderEntity(UUID customerId, OrderStatus status) {
        OrderEntity o = new OrderEntity();
        o.setOrderId(UUID.randomUUID());
        o.setQuoteId(UUID.randomUUID());
        o.setCustomerId(customerId);
        o.setProductId("HEALTH_BASIC");
        o.setFinalPremiumVnd(298_000L);
        o.setLine("health");
        o.setCoverageAmountVnd(100_000_000L);
        o.setDeductibleVnd(0L);
        o.setRiskProfile("{\"age\":30}");
        o.setStatus(status);
        o.setCreatedAt(OffsetDateTime.now());
        return o;
    }
}
