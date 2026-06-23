package dpp.notification.consumer;

import dpp.notification.service.NotificationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class NotificationEventListeners {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationEventListeners(NotificationService notificationService) {
        this.notificationService = notificationService;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "policy.issued.queue")
    public void onPolicyIssued(String msg) { handle(msg, "PolicyIssued", "Your policy has been issued."); }

    @RabbitListener(queues = "claim.status.changed.queue")
    public void onClaimChanged(String msg) { handle(msg, "ClaimStatusChanged", "Your claim status has changed."); }

    @RabbitListener(queues = "endorsement.applied.queue")
    public void onEndorsement(String msg) { handle(msg, "EndorsementApplied", "Your policy endorsement has been applied."); }

    @RabbitListener(queues = "policy.renewed.queue")
    public void onRenewed(String msg) { handle(msg, "PolicyRenewed", "Your policy has been renewed."); }

    @RabbitListener(queues = "policy.cancelled.queue")
    public void onCancelled(String msg) { handle(msg, "PolicyCancelled", "Your policy has been cancelled."); }

    private void handle(String msg, String type, String message) {
        try {
            JsonNode n = objectMapper.readTree(msg);
            UUID policyId = n.has("policy_id") && !n.get("policy_id").isNull() ? UUID.fromString(n.get("policy_id").asText()) : null;
            UUID customerId = n.has("customer_id") && !n.get("customer_id").isNull() ? UUID.fromString(n.get("customer_id").asText()) : null;
            notificationService.createNotification(customerId, policyId, type, message);
        } catch (Exception e) { throw new RuntimeException("Notification processing failed", e); }
    }
}
