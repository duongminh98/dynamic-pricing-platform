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
public class BillingClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public BillingClient(RestTemplate restTemplate,
                         @Value("${dpp.billing.base-url:http://localhost:8086}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public Map<String, Object> createInvoice(UUID orderId, UUID policyId, long amountVnd) {
        return createInvoice(orderId, policyId, amountVnd, null, null, null);
    }

    public Map<String, Object> createEndorsementInvoice(UUID orderId, UUID policyId, long amountVnd,
                                                         UUID endorsementRequestId, java.time.OffsetDateTime dueDate) {
        return createInvoice(orderId, policyId, amountVnd, endorsementRequestId, dueDate, null);
    }

    public Map<String, Object> createEndorsementInvoice(UUID orderId, UUID policyId, long amountVnd,
                                                         UUID endorsementRequestId, java.time.OffsetDateTime dueDate,
                                                         UUID customerId) {
        return createInvoice(orderId, policyId, amountVnd, endorsementRequestId, dueDate, customerId);
    }

    public Map<String, Object> createRenewalInvoice(UUID orderId, UUID policyId, long amountVnd, UUID customerId) {
        return createInvoice(orderId, policyId, amountVnd, null, null, customerId);
    }

    private Map<String, Object> createInvoice(UUID orderId, UUID policyId, long amountVnd,
                                               UUID endorsementRequestId, java.time.OffsetDateTime dueDate,
                                               UUID customerId) {
        String url = baseUrl + "/internal/invoices";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", orderId.toString());
        if (policyId != null) body.put("policy_id", policyId.toString());
        body.put("amount_vnd", amountVnd);
        if (endorsementRequestId != null) body.put("endorsement_request_id", endorsementRequestId.toString());
        if (dueDate != null) body.put("due_date", dueDate.toString());
        if (customerId != null) body.put("customer_id", customerId.toString());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            return restTemplate.postForObject(url, entity, Map.class);
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR, "Failed to create invoice: " + e.getMessage(), null);
        }
    }
}
