package dpp.order.client;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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
            throw new ServiceException(ErrorCode.BAD_REQUEST, "Quote not found: " + quoteId, null);
        }
    }
}
