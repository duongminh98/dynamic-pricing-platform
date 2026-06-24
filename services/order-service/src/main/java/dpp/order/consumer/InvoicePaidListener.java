package dpp.order.consumer;

import dpp.order.service.PolicyIssuanceService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class InvoicePaidListener {

    private final PolicyIssuanceService issuanceService;
    private final ObjectMapper objectMapper;

    public InvoicePaidListener(PolicyIssuanceService issuanceService) {
        this.issuanceService = issuanceService;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "invoice.paid.queue")
    public void onInvoicePaid(@Payload String message,
                              @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode node = objectMapper.readTree(message);
            UUID orderId = UUID.fromString(node.get("order_id").asText());
            UUID policyId = node.has("policy_id") && !node.get("policy_id").isNull()
                    ? UUID.fromString(node.get("policy_id").asText()) : null;
            issuanceService.issuePolicy(eventId, orderId, policyId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process InvoicePaid", e);
        }
    }
}
