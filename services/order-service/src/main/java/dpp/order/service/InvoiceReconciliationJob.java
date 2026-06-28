package dpp.order.service;

import dpp.common.outbox.OutboxPublisher;
import dpp.order.entity.EndorsementRequestEntity;
import dpp.order.entity.EndorsementStatus;
import dpp.order.entity.OrderEntity;
import dpp.order.entity.OrderStatus;
import dpp.order.entity.Policy;
import dpp.order.repository.EndorsementRequestRepository;
import dpp.order.repository.OrderRepository;
import dpp.order.repository.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class InvoiceReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(InvoiceReconciliationJob.class);

    private final OrderRepository orderRepository;
    private final EndorsementRequestRepository endorsementRequestRepository;
    private final PolicyRepository policyRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    @Value("${dpp.reconcile.invoice-stale-minutes:15}")
    private int staleMinutes;

    public InvoiceReconciliationJob(OrderRepository orderRepository,
                                     EndorsementRequestRepository endorsementRequestRepository,
                                     PolicyRepository policyRepository,
                                     OutboxPublisher outboxPublisher) {
        this.orderRepository = orderRepository;
        this.endorsementRequestRepository = endorsementRequestRepository;
        this.policyRepository = policyRepository;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    @Transactional
    public void reconcileStaleInvoices() {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(staleMinutes);
        int reEnqueued = 0;

        // Re-enqueue OrderApproved for orders stuck without an invoice
        List<OrderEntity> staleOrders = orderRepository
                .findStaleWithoutInvoice(OrderStatus.PENDING_PAYMENT, threshold);
        for (OrderEntity order : staleOrders) {
            enqueueOrderApproved(order);
            reEnqueued++;
        }

        // Re-enqueue EndorsementPendingPayment for endorsements stuck without an invoice
        List<EndorsementRequestEntity> staleEndorsements = endorsementRequestRepository
                .findStaleWithoutInvoice(EndorsementStatus.APPROVED_PENDING_PAYMENT, threshold);
        for (EndorsementRequestEntity req : staleEndorsements) {
            enqueueEndorsementPendingPayment(req);
            reEnqueued++;
        }

        if (reEnqueued > 0) {
            log.info("InvoiceReconciliation: re-enqueued {} stale entities (orders={}, endorsements={})",
                    reEnqueued, staleOrders.size(), staleEndorsements.size());
        }
    }

    private void enqueueOrderApproved(OrderEntity order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", order.getOrderId().toString());
        payload.put("customer_id", order.getCustomerId().toString());
        payload.put("product_id", order.getProductId());
        payload.put("line", order.getLine());
        payload.put("final_premium_vnd", order.getFinalPremiumVnd());
        payload.put("status", order.getStatus().name());
        try {
            outboxPublisher.enqueue("OrderApproved", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Failed to re-enqueue OrderApproved for order {}", order.getOrderId(), e);
        }
    }

    private void enqueueEndorsementPendingPayment(EndorsementRequestEntity req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customer_id", req.getCustomerId().toString());
        payload.put("endorsement_request_id", req.getEndorsementRequestId().toString());
        payload.put("invoice_id", "");
        payload.put("policy_id", req.getPolicyId().toString());
        payload.put("additional_charge_vnd", req.getQuotedPremiumVnd() != null ? req.getQuotedPremiumVnd() : 0);
        payload.put("due_date", req.getDueDate() != null ? req.getDueDate().toString() : "");
        // Resolve order_id from the policy
        Optional<Policy> policy = policyRepository.findById(req.getPolicyId());
        if (policy.isPresent()) {
            payload.put("order_id", policy.get().getOrderId().toString());
        }
        try {
            outboxPublisher.enqueue("EndorsementPendingPayment", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Failed to re-enqueue EndorsementPendingPayment for endorsement {}",
                    req.getEndorsementRequestId(), e);
        }
    }
}
