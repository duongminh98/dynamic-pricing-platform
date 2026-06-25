package dpp.billing.consumer;

import dpp.billing.service.AdjustmentService;
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
    private final ObjectMapper objectMapper;

    public BillingEventListeners(AdjustmentService adjustmentService) {
        this.adjustmentService = adjustmentService;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "endorsement.applied.queue")
    public void onEndorsement(@Payload String message,
                              @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode n = objectMapper.readTree(message);
            adjustmentService.applyEndorsement(eventId, UUID.fromString(n.get("policy_id").asText()),
                    n.get("premium_old").asLong(), n.get("premium_new").asLong(),
                    n.get("remaining_days").asLong(), n.get("term_days").asLong());
        } catch (Exception e) { throw new RuntimeException("Endorsement processing failed", e); }
    }

    @RabbitListener(queues = "policy.cancelled.queue")
    public void onCancellation(@Payload String message,
                               @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode n = objectMapper.readTree(message);
            adjustmentService.applyCancellation(eventId, UUID.fromString(n.get("policy_id").asText()),
                    n.get("final_premium_vnd").asLong(),
                    n.get("remaining_days").asLong(), n.get("term_days").asLong());
        } catch (Exception e) { throw new RuntimeException("Cancellation processing failed", e); }
    }
}
