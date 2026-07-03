package dpp.order.client;

import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Compatibility type for legacy unit tests.
 * Order-service runtime pricing uses RepriceRequested/RepriceCompleted events.
 */
public class PricingClient {
    public PricingClient() {
    }

    public PricingClient(RestTemplate ignoredRestTemplate, String ignoredBaseUrl) {
    }

    public Map<String, Object> rerate(String productId, Map<String, Object> profile) {
        return Map.of();
    }
}
