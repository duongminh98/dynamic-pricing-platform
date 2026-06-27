package dpp.billing.consumer;

import dpp.billing.service.AdjustmentService;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import dpp.billing.service.RefundService;
import dpp.billing.dto.CreateInvoiceRequest;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class BillingEventListeners {

    private final AdjustmentService adjustmentService;
    private final BillingService billingService;
    private final CreditService creditService;
    private final RefundService refundService;
    private final ObjectMapper objectMapper;

    public BillingEventListeners(AdjustmentService adjustmentService, BillingService billingService,
                                 CreditService creditService, RefundService refundService) {
        this.adjustmentService = adjustmentService;
        this.billingService = billingService;
        this.creditService = creditService;
        this.refundService = refundService;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "endorsement.applied.queue")
    public void onEndorsement(@Payload String message,
                              @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode n = objectMapper.readTree(message);
            adjustmentService.applyEndorsement(eventId, UUID.fromString(n.get("policy_id").asText()),
                    UUID.fromString(n.get("order_id").asText()),
                    n.get("premium_old").asLong(), n.get("premium_new").asLong(),
                    n.get("remaining_days").asLong(), n.get("term_days").asLong());
        } catch (Exception e) { throw new RuntimeException("Endorsement processing failed", e); }
    }

    @RabbitListener(queues = "policy.cancelled.queue")
    public void onCancellation(@Payload String message,
                               @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode n = objectMapper.readTree(message);
            UUID policyId = UUID.fromString(n.get("policy_id").asText());
            adjustmentService.applyCancellation(eventId, policyId,
                    n.get("final_premium_vnd").asLong(),
                    n.get("remaining_days").asLong(), n.get("term_days").asLong());
            // Auto-create refund requests for remaining credits on cancelled policies.
            String customerIdStr = n.has("customer_id") ? n.get("customer_id").asText() : null;
            if (customerIdStr != null) {
                refundService.createRefundsForCancelledPolicy(policyId, UUID.fromString(customerIdStr));
            }
        } catch (Exception e) { throw new RuntimeException("Cancellation processing failed", e); }
    }

    @RabbitListener(queues = "policy.renewed.queue")
    public void onRenewal(@Payload String message,
                          @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode n = objectMapper.readTree(message);
            CreateInvoiceRequest req = new CreateInvoiceRequest();
            req.setOrderId(UUID.fromString(n.get("order_id").asText()));
            req.setPolicyId(UUID.fromString(n.get("policy_id").asText()));
            req.setAmountVnd(n.get("final_premium_vnd").asLong());
            billingService.createInvoice(req);
        } catch (Exception e) { throw new RuntimeException("Renewal processing failed", e); }
    }

    @RabbitListener(queues = "endorsement.credit.issued.queue")
    public void onCreditIssued(@Payload String message,
                               @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode n = objectMapper.readTree(message);
            creditService.createCredit(eventId,
                    UUID.fromString(n.get("policy_id").asText()),
                    UUID.fromString(n.get("customer_id").asText()),
                    UUID.fromString(n.get("endorsement_request_id").asText()),
                    n.get("amount_vnd").asLong());
        } catch (Exception e) { throw new RuntimeException("Credit issued processing failed", e); }
    }
}
