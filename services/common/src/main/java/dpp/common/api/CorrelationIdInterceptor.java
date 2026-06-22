package dpp.common.api;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * RestTemplate interceptor that propagates X-Correlation-Id on outgoing
 * service-to-service calls (R19.6).
 *
 * <p>Registered as a bean by {@link dpp.common.config.CommonAutoConfiguration}.</p>
 *
 * Usage: register on RestTemplate beans via
 * {@code restTemplate.setInterceptors(List.of(new CorrelationIdInterceptor()))}
 */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            request.getHeaders().set(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        return execution.execute(request, body);
    }
}
