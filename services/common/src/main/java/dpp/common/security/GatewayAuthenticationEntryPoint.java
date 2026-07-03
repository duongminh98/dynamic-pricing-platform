package dpp.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.common.api.CorrelationIdFilter;
import dpp.common.api.ErrorCode;
import dpp.common.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

public class GatewayAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        ErrorResponse body = ErrorResponse.of(ErrorCode.UNAUTHENTICATED,
                correlationId != null ? correlationId : "");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
