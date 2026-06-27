package dpp.order.service;

import dpp.common.security.CustomerId;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.dto.*;
import dpp.order.entity.*;
import dpp.order.client.*;
import dpp.order.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final PricingClient pricingClient;
    private final BillingClient billingClient;
    private final OutboxPublisher outboxPublisher;
    private final PolicyRepository policyRepository;
    private final OrderApprovalTransactionService approvalTxService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> INDEMNITY_LINES = Set.of("motorbike", "car", "home", "health", "travel");

    public OrderService(OrderRepository orderRepository, PricingClient pricingClient, BillingClient billingClient,
                        OutboxPublisher outboxPublisher, PolicyRepository policyRepository,
                        OrderApprovalTransactionService approvalTxService) {
        this.orderRepository = orderRepository;
        this.pricingClient = pricingClient;
        this.billingClient = billingClient;
        this.outboxPublisher = outboxPublisher;
        this.policyRepository = policyRepository;
        this.approvalTxService = approvalTxService;
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
        // Persist the full risk profile that was priced so it can be propagated to the
        // issued policy and used as the re-rate base for endorsements (R23.2/R23.8).
        Object profile = quote.get("profile");
        if (profile instanceof Map<?, ?> profileMap && !profileMap.isEmpty()) {
            try {
                order.setRiskProfile(objectMapper.writeValueAsString(profileMap));
            } catch (Exception e) {
                order.setRiskProfile(null);
            }
        }

        // Duplicate active policy check for indemnity-based lines.
        String line = order.getLine();
        if (line != null && INDEMNITY_LINES.contains(line)) {
            String assetKey = extractAssetKey(line, profile);
            if (assetKey != null) {
                UUID customerId = order.getCustomerId();
                if (policyRepository.existsActivePolicy(customerId, assetKey)) {
                    throw new ServiceException(ErrorCode.DUPLICATE_ACTIVE_POLICY,
                            "You already have an active policy for this asset",
                            Map.of("line", line, "asset_key", assetKey));
                }
            }
        }

        order.setStatus(OrderStatus.PENDING_REVIEW);
        order.setCreatedAt(OffsetDateTime.now());
        order = orderRepository.save(order);
        enqueueOrderEvent("OrderSubmitted", order);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> myOrders(String keycloakSubject) {
        UUID customerId = resolveCustomerId(keycloakSubject);
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderResponse getMyOrder(String keycloakSubject, UUID orderId) {
        OrderEntity order = findOrder(orderId);
        UUID customerId = resolveCustomerId(keycloakSubject);
        if (!order.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public Page<ReviewQueueItem> reviewQueue(int page, int size, String line) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        Page<OrderEntity> orders = (line != null && !line.isBlank())
                ? orderRepository.findByStatusAndLine(OrderStatus.PENDING_REVIEW, line, pageable)
                : orderRepository.findByStatus(OrderStatus.PENDING_REVIEW, pageable);
        return orders.map(this::toQueueItem);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {
        return toResponse(findOrder(orderId));
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> adminListOrders(OrderStatus status, UUID customerId, String line, int page, int size) {
        size = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<OrderEntity> orders = orderRepository.findFiltered(status, customerId, line, pageable);
        return orders.map(this::toResponse);
    }

    public OrderResponse approve(UUID orderId, String reviewer) {
        OrderEntity order = findOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING_REVIEW) {
            throw new ServiceException(ErrorCode.ORDER_NOT_APPROVED);
        }
        // Invoice creation: cross-service REST, OUTSIDE the DB transaction (billing dedups on order_id).
        Map<String, Object> invoiceResp = billingClient.createInvoice(order.getOrderId(), null, order.getFinalPremiumVnd());
        UUID invoiceId = invoiceResp != null && invoiceResp.get("invoice_id") != null
                ? UUID.fromString(String.valueOf(invoiceResp.get("invoice_id"))) : null;
        // DB write + outbox enqueue in the same transaction (atomic).
        OrderEntity approved = approvalTxService.approveWithInvoice(orderId, reviewer, invoiceId);
        return toResponse(approved);
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
        enqueueOrderEvent("OrderRejected", order);
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

    /**
     * Extract a unique asset key from the risk profile for indemnity-based lines.
     * motorbike/car: vehicle_plate
     * home: property_address
     * health: customer_id (the person itself is the asset)
     * travel: destination_country + trip_start_date + trip_end_date
     * accident: null (benefit-based, no duplicate check)
     */
    @SuppressWarnings("unchecked")
    private String extractAssetKey(String line, Object profile) {
        if (!(profile instanceof Map<?, ?> profileMap)) {
            return null;
        }
        return switch (line) {
            case "motorbike", "car" -> {
                Object plate = profileMap.get("vehicle_plate");
                yield plate != null ? plate.toString() : null;
            }
            case "home" -> {
                Object addr = profileMap.get("property_address");
                yield addr != null ? addr.toString() : null;
            }
            case "health" -> {
                Object customerId = profileMap.get("customer_id");
                yield customerId != null ? customerId.toString() : null;
            }
            case "travel" -> {
                Object country = profileMap.get("destination_country");
                Object startDate = profileMap.get("trip_start_date");
                Object endDate = profileMap.get("trip_end_date");
                if (country != null && startDate != null && endDate != null) {
                    yield country + "|" + startDate + "|" + endDate;
                }
                yield null;
            }
            default -> null;
        };
    }

    private void enqueueOrderEvent(String type, OrderEntity order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", order.getOrderId().toString());
        payload.put("customer_id", order.getCustomerId().toString());
        payload.put("product_id", order.getProductId());
        payload.put("line", order.getLine());
        payload.put("final_premium_vnd", order.getFinalPremiumVnd());
        payload.put("status", order.getStatus().name());
        payload.put("created_at", order.getCreatedAt() != null ? order.getCreatedAt().toString() : null);
        payload.put("review_reason", order.getReviewReason());
        try {
            outboxPublisher.enqueue(type, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue " + type, e);
        }
    }

    private ReviewQueueItem toQueueItem(OrderEntity order) {
        ReviewQueueItem item = new ReviewQueueItem();
        item.setOrderId(order.getOrderId());
        item.setCustomerId(order.getCustomerId());
        item.setProductId(order.getProductId());
        item.setFinalPremiumVnd(order.getFinalPremiumVnd());
        item.setStatus(order.getStatus());
        item.setLine(order.getLine());
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
        resp.setInvoiceId(order.getInvoiceId());
        return resp;
    }
}
