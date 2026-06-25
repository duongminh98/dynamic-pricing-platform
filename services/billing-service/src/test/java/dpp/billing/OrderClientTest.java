package dpp.billing;

import dpp.billing.client.OrderClient;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderClientTest {

    @Test
    void getPolicyOwnerReturnsCustomerId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID policyId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(Map.of("customer_id", customerId.toString()));

        OrderClient client = new OrderClient(restTemplate, "http://localhost:8083");
        UUID result = client.getPolicyOwner(policyId);
        assertEquals(customerId, result);
    }

    @Test
    void getPolicyOwnerReturnsNullWhenNoCustomerId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID policyId = UUID.randomUUID();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(Map.of());

        OrderClient client = new OrderClient(restTemplate, "http://localhost:8083");
        UUID result = client.getPolicyOwner(policyId);
        assertNull(result);
    }

    @Test
    void getPolicyOwnerReturnsNullWhenResponseIsNull() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID policyId = UUID.randomUUID();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(null);

        OrderClient client = new OrderClient(restTemplate, "http://localhost:8083");
        UUID result = client.getPolicyOwner(policyId);
        assertNull(result);
    }

    @Test
    void getPolicyOwnerThrowsOnRestException() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID policyId = UUID.randomUUID();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        OrderClient client = new OrderClient(restTemplate, "http://localhost:8083");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> client.getPolicyOwner(policyId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getOrderOwnerReturnsCustomerId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(Map.of("customer_id", customerId.toString()));

        OrderClient client = new OrderClient(restTemplate, "http://localhost:8083");
        UUID result = client.getOrderOwner(orderId);
        assertEquals(customerId, result);
    }

    @Test
    void getOrderOwnerReturnsNullWhenNoCustomerId() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID orderId = UUID.randomUUID();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(Map.of());

        OrderClient client = new OrderClient(restTemplate, "http://localhost:8083");
        UUID result = client.getOrderOwner(orderId);
        assertNull(result);
    }

    @Test
    void getOrderOwnerReturnsNullWhenResponseIsNull() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID orderId = UUID.randomUUID();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(null);

        OrderClient client = new OrderClient(restTemplate, "http://localhost:8083");
        UUID result = client.getOrderOwner(orderId);
        assertNull(result);
    }

    @Test
    void getOrderOwnerThrowsOnRestException() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        UUID orderId = UUID.randomUUID();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        OrderClient client = new OrderClient(restTemplate, "http://localhost:8083");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> client.getOrderOwner(orderId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }
}
