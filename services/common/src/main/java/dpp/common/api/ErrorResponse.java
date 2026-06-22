package dpp.common.api;

import lombok.Getter;

/**
 * Structured error response matching design §7.1 schema.
 * No PII/secret is ever included (R18.4, R19.3).
 */
@Getter
public class ErrorResponse {

    private final String error_code;
    private final String message;
    private final String correlation_id;
    private final Object details;

    private ErrorResponse(String errorCode, String message, String correlationId, Object details) {
        this.error_code = errorCode;
        this.message = message;
        this.correlation_id = correlationId;
        this.details = details;
    }

    public static ErrorResponse of(ErrorCode errorCode, String correlationId) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getDefaultMessage(), correlationId, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String correlationId, Object details) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getDefaultMessage(), correlationId, details);
    }

    public static ErrorResponse of(String errorCode, String message, String correlationId, Object details) {
        return new ErrorResponse(errorCode, message, correlationId, details);
    }
}
