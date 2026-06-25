package dpp.order.service;

import dpp.common.security.CustomerId;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.order.dto.*;
import dpp.order.entity.*;
import dpp.order.client.*;
import dpp.order.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final PricingClient pricingClient;
    private final BillingClient billingClient;

    public OrderService(OrderRepository orderRepository, PricingClient pricingClient, BillingClient billingClient) {
        this.orderRepository = orderRepository;
        this.pricingClient = pricingClient;
        this.billingClient = billingClient;
    }

    @Transactional
    public OrderResponse createOrder(String keycloakSubject, CreateOrderRequest request) {
        UUID quoteId = request.getQuoteId();
        if (orderRepository.findByQuoteId(quoteId).isPresent()) {
            throw new ServiceException(ErrorCode.QUOTE_ALREADY_USED);
        }
        Map<String, Object> quote = pricingClient.getQuote(quoteId);
        String expiresAtStr = String.valueOf(quote.get("expires_at"));
        OffsetDateTime expiresAt = OffsetDateTime.parse(expiresAtStr);
        if (expiresAt.isBefore(OffsetDateTime.now())) {
            throw new ServiceException(ErrorCode.QUOTE_EXPIRED);
        }
        long finalPremiumVnd = ((Number) quote.get("final_premium_vnd")).longValue();
        String productId = String.valueOf(quote.get("product_id"));
        OrderEntity order = new OrderEntity();
        order.setOrderId(UUID.randomUUID());
        order.setQuoteId(quoteId);
        order.setCustomerId(resolveCustomerId(keycloakSubject));
        order.setProductId(productId);
        order.setFinalPremiumVnd(finalPremiumVnd);
        order.setLine(quote.get("line") != null ? String.valueOf(quote.get("line")) : null);
        Object tripDays = quote.get("trip_duration_days");
        order.setTripDurationDays(tripDays instanceof Number ? ((Number) tripDays).intValue() : null);
        Object coverage = quote.get("coverage_amount_vnd");
        order.setCoverageAmountVnd(coverage instanceof Number ? ((Number) coverage).longValue() : null);
        Object deductible = quote.get("deductible_vnd");
        order.setDeductibleVnd(deductible instanceof Number ? ((Number) deductible).longValue() : null);
        order.setStatus(OrderStatus.PENDING_REVIEW);
        order.setCreatedAt(OffsetDateTime.now());
        order = orderRepository.save(order);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<ReviewQueueItem> reviewQueue() {
        return orderRepository.findByStatusOrderByCreatedAtAsc(OrderStatus.PENDING_REVIEW).stream()
                .map(this::toQueueItem).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {
        return toResponse(findOrder(orderId));
    }

    public OrderResponse approve(UUID orderId, String reviewer) {
        OrderEntity order = approveInTransaction(orderId, reviewer);
        // R6.10 / R19.4: invoice creation is a cross-service REST call kept OUT of the
        // DB transaction to avoid dual-write. Billing dedups on order_id (findByOrderId),
        // so a retry after a transient failure is safe.
        billingClient.createInvoice(order.getOrderId(), null, order.getFinalPremiumVnd());
        return toResponse(order);
    }

    @Transactional
    protected OrderEntity approveInTransaction(UUID orderId, String reviewer) {
        OrderEntity order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING_REVIEW) {
            throw new ServiceException(ErrorCode.ORDER_NOT_APPROVED);
        }
        order.setReviewDecision(ReviewDecision.APPROVE);
        order.setReviewedBy(reviewer);
        order.setReviewedAt(OffsetDateTime.now());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        return orderRepository.save(order);
    }

    @Transactional
    public OrderResponse reject(UUID orderId, String reason, String reviewer) {
        OrderEntity order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING_REVIEW) {
            throw new ServiceException(ErrorCode.ORDER_NOT_APPROVED);
        }
        order.setReviewDecision(ReviewDecision.REJECT);
        order.setReviewReason(reason);
        order.setReviewedBy(reviewer);
        order.setReviewedAt(OffsetDateTime.now());
        order.setStatus(OrderStatus.REJECTED);
        order = orderRepository.save(order);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderEntity findOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found", null));
    }

    private UUID resolveCustomerId(String keycloakSubject) {
        return CustomerId.fromSubject(keycloakSubject);
    }

    private ReviewQueueItem toQueueItem(OrderEntity order) {
        ReviewQueueItem item = new ReviewQueueItem();
        item.setOrderId(order.getOrderId());
        item.setCustomerId(order.getCustomerId());
        item.setProductId(order.getProductId());
        item.setFinalPremiumVnd(order.getFinalPremiumVnd());
        item.setStatus(order.getStatus());
        item.setCreatedAt(order.getCreatedAt());
        return item;
    }

    private OrderResponse toResponse(OrderEntity order) {
        OrderResponse resp = new OrderResponse();
        resp.setOrderId(order.getOrderId());
        resp.setQuoteId(order.getQuoteId());
        resp.setCustomerId(order.getCustomerId());
        resp.setProductId(order.getProductId());
        resp.setFinalPremiumVnd(order.getFinalPremiumVnd());
        resp.setStatus(order.getStatus());
        resp.setReviewDecision(order.getReviewDecision());
        resp.setReviewReason(order.getReviewReason());
        resp.setReviewedBy(order.getReviewedBy());
        resp.setReviewedAt(order.getReviewedAt());
        resp.setCreatedAt(order.getCreatedAt());
        return resp;
    }
}
