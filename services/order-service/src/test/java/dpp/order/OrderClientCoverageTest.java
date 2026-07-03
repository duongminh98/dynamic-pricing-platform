package dpp.order;

import dpp.order.client.BillingClient;
import dpp.order.client.PricingClient;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderClientCoverageTest {

    @Test
    void pricingClientRerateIsCompatibilityNoop() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        PricingClient client = new PricingClient(restTemplate, "http://localhost:8000");

        Map<String, Object> result = client.rerate("motor-basic", Map.of("bmi", 22.5));

        assertTrue(result.isEmpty());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void billingClientCreateInvoiceReturnsMap() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID orderId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Map<String, Object> mockResponse = Map.of("invoice_id", UUID.randomUUID().toString());
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockResponse);

        BillingClient client = new BillingClient(restTemplate, "http://localhost:8086");
        Map<String, Object> result = client.createInvoice(orderId, policyId, 1_000_000L);

        assertNotNull(result);
    }

    @Test
    void billingClientCreateInvoiceWithNullPolicyId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID orderId = UUID.randomUUID();
        Map<String, Object> mockResponse = Map.of("invoice_id", UUID.randomUUID().toString());
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(mockResponse);

        BillingClient client = new BillingClient(restTemplate, "http://localhost:8086");
        Map<String, Object> result = client.createInvoice(orderId, null, 1_000_000L);

        assertNotNull(result);
    }

    @Test
    void billingClientCreateInvoiceThrowsOnException() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        BillingClient client = new BillingClient(restTemplate, "http://localhost:8086");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> client.createInvoice(UUID.randomUUID(), null, 1_000_000L));
        assertEquals(ErrorCode.INTERNAL_ERROR, ex.getErrorCode());
    }
}
