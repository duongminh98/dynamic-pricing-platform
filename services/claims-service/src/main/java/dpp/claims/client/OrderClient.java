package dpp.claims.client;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OrderClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public OrderClient(RestTemplate restTemplate,
                       @Value("${dpp.order.base-url:http://localhost:8000}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPolicy(UUID policyId) {
        try {
            return restTemplate.getForObject(baseUrl + "/policies/" + policyId, Map.class);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Policy not found", null);
        }
    }
}
