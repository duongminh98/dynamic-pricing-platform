package dpp.order.client;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class PricingClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PricingClient(RestTemplate restTemplate,
                         @Value("${dpp.pricing.base-url:http://localhost:8000}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public Map<String, Object> getQuote(UUID quoteId) {
        String url = baseUrl + "/pricing/quote/" + quoteId;
        try {
            return restTemplate.getForObject(url, Map.class);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Quote not found: " + quoteId, null);
        }
    }

    /**
     * Re-rate a product for a new risk profile (R23.2/R23.8 endorsement re-rating).
     * Posts a fresh quote request and returns the resulting quote payload, which
     * carries {@code final_premium_vnd}. The profile holds the new risk attributes.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rerate(String productId, Map<String, Object> profile) {
        String url = baseUrl + "/pricing/quote";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_id", productId);
        body.put("profile", profile);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR, "Re-rating failed: " + e.getMessage(), null);
        }
    }
}
