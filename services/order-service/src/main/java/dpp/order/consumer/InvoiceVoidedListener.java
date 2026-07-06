package dpp.order.consumer;

import dpp.order.service.PolicyLifecycleService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class InvoiceVoidedListener {

    private final PolicyLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    public InvoiceVoidedListener(PolicyLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "invoice.voided.order.queue")
    public void onInvoiceVoided(@Payload String message,
                                @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode node = objectMapper.readTree(message);
            // Only endorsement adjustment invoices affect order-state. An admin voiding one
            // directly (billing tab) must terminate the held endorsement, else it sticks in
            // APPROVED_PENDING_PAYMENT with no payable invoice. Match on invoice_id so a stale
            // event (e.g. the old invoice from extendDueDate) can't void the revived endorsement.
            if (node.has("endorsement_request_id") && !node.get("endorsement_request_id").isNull()) {
                UUID endorsementRequestId = UUID.fromString(node.get("endorsement_request_id").asText());
                UUID invoiceId = node.has("invoice_id") && !node.get("invoice_id").isNull()
                        ? UUID.fromString(node.get("invoice_id").asText()) : null;
                lifecycleService.voidEndorsementForVoidedInvoice(endorsementRequestId, invoiceId);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to process InvoiceVoided", e);
        }
    }
}
