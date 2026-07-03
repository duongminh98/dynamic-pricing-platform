package dpp.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.common.api.CorrelationIdFilter;
import dpp.common.api.ErrorCode;
import dpp.common.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

public class GatewayAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        ErrorResponse body = ErrorResponse.of(ErrorCode.FORBIDDEN_RESOURCE,
                correlationId != null ? correlationId : "");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
