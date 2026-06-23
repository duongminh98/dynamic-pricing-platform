package dpp.order.client;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class BillingClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public BillingClient(RestTemplate restTemplate,
                         @Value("${dpp.billing.base-url:http://localhost:8000}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public Map<String, Object> createInvoice(UUID orderId, UUID policyId, long amountVnd) {
        String url = baseUrl + "/billing/invoices";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", orderId.toString());
        if (policyId != null) body.put("policy_id", policyId.toString());
        body.put("amount_vnd", amountVnd);
        try {
            return restTemplate.postForObject(url, body, Map.class);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR, "Failed to create invoice", null);
        }
    }
}
