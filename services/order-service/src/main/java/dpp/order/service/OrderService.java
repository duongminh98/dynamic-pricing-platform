package dpp.order.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.order.dto.CreateOrderRequest;
import dpp.order.dto.OrderResponse;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.client.PricingClient;
import dpp.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final PricingClient pricingClient;

    public OrderService(OrderRepository orderRepository, PricingClient pricingClient) {
        this.orderRepository = orderRepository;
        this.pricingClient = pricingClient;
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
        order.setStatus(OrderStatus.PENDING_REVIEW);
        order.setCreatedAt(OffsetDateTime.now());

        order = orderRepository.save(order);
        return toResponse(order);
    }

    private UUID resolveCustomerId(String keycloakSubject) {
        return UUID.nameUUIDFromBytes(keycloakSubject.getBytes());
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
