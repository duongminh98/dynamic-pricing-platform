package dpp.billing.client;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Reads policy ownership from Order_Service so Billing can enforce the
 * data-ownership isolation rule (BR-10, R33.5) without holding a customer_id
 * of its own. The order service returns the policy with its customer_id.
 */
@Component
public class OrderClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public OrderClient(RestTemplate restTemplate,
                       @Value("${dpp.order.base-url:http://localhost:8083}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /**
     * Fetch the owning customer_id for a policy. Returns null when the policy is
     * unknown so callers can map it to a not-found / forbidden decision.
     */
    @SuppressWarnings("unchecked")
    public UUID getPolicyOwner(UUID policyId) {
        try {
            Map<String, Object> policy = restTemplate.getForObject(
                    baseUrl + "/internal/policies/" + policyId + "/owner", Map.class);
            if (policy == null) {
                return null;
            }
            Object customerId = policy.get("customer_id");
            return customerId != null ? UUID.fromString(customerId.toString()) : null;
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null);
        }
    }

    /**
     * Fetch the owning customer_id for an order (task 20.13). Invoices created at
     * order approval carry order_id with a null policy_id (the policy is issued
     * only after payment), so the pay-ownership check resolves the owner through
     * the order. Returns null when the order has no customer so callers can map it
     * to a forbidden decision.
     */
    @SuppressWarnings("unchecked")
    public UUID getOrderOwner(UUID orderId) {
        try {
            Map<String, Object> order = restTemplate.getForObject(
                    baseUrl + "/internal/orders/" + orderId + "/owner", Map.class);
            if (order == null) {
                return null;
            }
            Object customerId = order.get("customer_id");
            return customerId != null ? UUID.fromString(customerId.toString()) : null;
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found", null);
        }
    }
}
