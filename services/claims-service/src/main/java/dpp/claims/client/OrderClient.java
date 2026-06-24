package dpp.claims.client;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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
                       @Value("${dpp.order.base-url:http://localhost:8083}") String baseUrl) {
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

    /**
     * Fetch the policy's exposure segments (design 5.5, R27.3). Each map carries
     * snake_case keys matching the order-service contract: exposure_segment_seq,
     * segment_start, segment_end, coverage_amount_vnd, deductible_vnd.
     */
    public List<Map<String, Object>> getExposureSegments(UUID policyId) {
        try {
            ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                    baseUrl + "/policies/" + policyId + "/exposure-segments",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> body = resp.getBody();
            return body != null ? body : List.of();
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Exposure segments not found", null);
        }
    }
}
