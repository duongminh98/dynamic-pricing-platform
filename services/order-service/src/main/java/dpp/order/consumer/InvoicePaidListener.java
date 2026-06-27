package dpp.order.consumer;

import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.repository.PolicyRepository;
import dpp.order.service.PolicyIssuanceService;
import dpp.order.service.PolicyLifecycleService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

@Component
public class InvoicePaidListener {

    private final PolicyIssuanceService issuanceService;
    private final PolicyLifecycleService lifecycleService;
    private final PolicyRepository policyRepository;
    private final ObjectMapper objectMapper;

    public InvoicePaidListener(PolicyIssuanceService issuanceService,
                               PolicyLifecycleService lifecycleService,
                               PolicyRepository policyRepository) {
        this.issuanceService = issuanceService;
        this.lifecycleService = lifecycleService;
        this.policyRepository = policyRepository;
        this.objectMapper = new ObjectMapper();
    }

    @RabbitListener(queues = "invoice.paid.queue")
    public void onInvoicePaid(@Payload String message,
                              @Header(name = "X-Event-Id", required = false) String eventId) {
        try {
            JsonNode node = objectMapper.readTree(message);
            // Branch 1: endorsement adjustment invoice.
            if (node.has("endorsement_request_id") && !node.get("endorsement_request_id").isNull()) {
                UUID endorsementRequestId = UUID.fromString(node.get("endorsement_request_id").asText());
                lifecycleService.applyPendingEndorsement(endorsementRequestId);
                return;
            }
            // Branch 2: renewal activation — policy exists, pending_payment, isRenewal.
            if (node.has("policy_id") && !node.get("policy_id").isNull()) {
                UUID policyId = UUID.fromString(node.get("policy_id").asText());
                Optional<Policy> existing = policyRepository.findById(policyId);
                if (existing.isPresent()) {
                    Policy p = existing.get();
                    if (p.getStatus() == PolicyStatus.pending_payment && p.isRenewal()) {
                        lifecycleService.activateRenewedPolicy(policyId);
                        return;
                    }
                }
            }
            // Branch 3: original order issuance.
            UUID orderId = UUID.fromString(node.get("order_id").asText());
            UUID policyId = node.has("policy_id") && !node.get("policy_id").isNull()
                    ? UUID.fromString(node.get("policy_id").asText()) : null;
            issuanceService.issuePolicy(eventId, orderId, policyId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process InvoicePaid", e);
        }
    }
}
