package dpp.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler producing structured error responses per design §7.1.
 * No PII or secret is ever included in the response (R18.4, R19.3).
 *
 * <p>Registered as a bean by {@link dpp.common.config.CommonAutoConfiguration}.
 * @RestControllerAdvice is automatically detected when this is a Spring bean.</p>
 *
 * Schema: { "error_code": "...", "message": "...", "correlation_id": "...", "details": {} }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private String correlationId() {
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        return cid != null ? cid : "";
    }

    // ── BusinessException ───────────────────────────────────────

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ErrorResponse> handleServiceException(ServiceException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        log.warn("ServiceException [{}]: {} — details={}", code.getCode(), ex.getMessage(), ex.getDetails());
        ErrorResponse body = ErrorResponse.of(code, correlationId(), ex.getDetails());
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    // ── Validation ──────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"));

        log.warn("Validation failed: {}", fieldErrors);
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.BAD_REQUEST, correlationId(), fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        // Malformed body or invalid enum value -> 400 with neutral code (not 500).
        log.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        ErrorResponse body = ErrorResponse.of(ErrorCode.BAD_REQUEST, correlationId());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, String> detail = Map.of(
                "field", ex.getName(),
                "expected_type", ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown"
        );
        log.warn("Type mismatch: field={} expected={}", ex.getName(), ex.getRequiredType());
        ErrorResponse body = ErrorResponse.of(ErrorCode.BAD_REQUEST, correlationId(), detail);
        return ResponseEntity.badRequest().body(body);
    }

    // ── Access Denied ────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.of(ErrorCode.FORBIDDEN_RESOURCE, correlationId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // ── Fallback ────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception ex) {
        log.error("Unhandled exception", ex);
        // Deliberately do NOT expose exception message (may contain PII/stack)
        ErrorResponse body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR, correlationId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
