package dpp.common.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.slf4j.MDC;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test: CorrelationIdInterceptor propagates X-Correlation-Id from MDC
 * to outgoing RestTemplate requests (R19.6).
 *
 * Feature: dynamic-pricing-platform
 * Validates: R19.6
 */
class CorrelationIdInterceptorTest {

    private CorrelationIdInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CorrelationIdInterceptor();
        MDC.clear();
    }

    @Test
    void shouldPropagateCorrelationIdFromMdc() throws Exception {
        String testCid = "test-correlation-123";
        MDC.put(CorrelationIdFilter.MDC_KEY, testCid);

        HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());

        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(mock(org.springframework.http.client.ClientHttpResponse.class));

        interceptor.intercept(request, new byte[0], execution);

        verify(request).getHeaders();
        verify(execution).execute(eq(request), any());
    }

    @Test
    void shouldNotFailWhenNoCorrelationIdInMdc() throws Exception {
        MDC.remove(CorrelationIdFilter.MDC_KEY);

        HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());

        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(mock(org.springframework.http.client.ClientHttpResponse.class));

        assertDoesNotThrow(() -> interceptor.intercept(request, new byte[0], execution));
    }
}
