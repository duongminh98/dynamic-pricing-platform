package dpp.notification.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Internal client to resolve a customer's email from Customer_Service (design 5.1).
 * Calls GET /internal/customers/{customerId}/email -- a service-to-service
 * endpoint not exposed through Kong. Returns null on any failure so the
 * notification service can gracefully fall back to in-app only.
 */
@Component
public class CustomerClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CustomerClient(RestTemplate restTemplate,
                          @Value("${dpp.customer.base-url:http://localhost:8082}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @SuppressWarnings("unchecked")
    public String getEmail(UUID customerId) {
        try {
            Map<String, Object> resp = restTemplate.getForObject(
                    baseUrl + "/internal/customers/" + customerId + "/email", Map.class);
            if (resp != null && resp.containsKey("email")) {
                return (String) resp.get("email");
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to fetch email for customer {}: {}", customerId, e.getMessage());
            return null;
        }
    }
}
