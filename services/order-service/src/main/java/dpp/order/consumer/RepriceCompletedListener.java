package dpp.order.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.order.service.PolicyLifecycleService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class RepriceCompletedListener {
    private final PolicyLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    public RepriceCompletedListener(PolicyLifecycleService lifecycleService, ObjectMapper objectMapper) {
        this.lifecycleService = lifecycleService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "reprice.completed.order.queue")
    public void onRepriceCompleted(@Payload String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String pricingRequestId = node.get("pricing_request_id").asText();
            String workflow = node.get("workflow").asText();
            Long finalPremium = node.hasNonNull("final_premium_vnd") ? node.get("final_premium_vnd").asLong() : null;
            String failureReason = node.hasNonNull("failure_reason") ? node.get("failure_reason").asText() : null;
            lifecycleService.handleRepriceCompleted(pricingRequestId, workflow, finalPremium, failureReason);
        } catch (Exception e) {
            throw new RuntimeException("RepriceCompleted processing failed", e);
        }
    }
}
