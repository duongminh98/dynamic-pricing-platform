package dpp.order.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.ReviewDecision;
import dpp.order.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates the transactional part of order approval so that the DB write
 * (status → PENDING_PAYMENT, invoice_id) and the outbox enqueue happen in the
 * same Spring-managed transaction. This avoids the self-invocation problem where
 * {@code @Transactional} on an internal method call is bypassed by the proxy.
 */
@Service
public class OrderApprovalTransactionService {

    private final OrderRepository orderRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public OrderApprovalTransactionService(OrderRepository orderRepository,
                                           OutboxPublisher outboxPublisher) {
        this.orderRepository = orderRepository;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public OrderEntity approveWithInvoice(UUID orderId, String reviewer, UUID invoiceId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found", null));
        if (order.getStatus() != OrderStatus.PENDING_REVIEW) {
            throw new ServiceException(ErrorCode.ORDER_NOT_APPROVED);
        }
        order.setReviewDecision(ReviewDecision.APPROVE);
        order.setReviewedBy(reviewer);
        order.setReviewedAt(OffsetDateTime.now());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setInvoiceId(invoiceId);
        orderRepository.save(order);
        enqueueOrderEvent("OrderApproved", order, invoiceId);
        return order;
    }

    private void enqueueOrderEvent(String type, OrderEntity order, UUID invoiceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", order.getOrderId().toString());
        payload.put("customer_id", order.getCustomerId().toString());
        payload.put("product_id", order.getProductId());
        payload.put("line", order.getLine());
        payload.put("final_premium_vnd", order.getFinalPremiumVnd());
        payload.put("status", order.getStatus().name());
        if (invoiceId != null) {
            payload.put("invoice_id", invoiceId.toString());
        }
        try {
            outboxPublisher.enqueue(type, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue " + type, e);
        }
    }
}
