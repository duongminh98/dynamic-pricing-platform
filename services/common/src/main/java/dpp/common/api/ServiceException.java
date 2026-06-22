package dpp.common.api;

import lombok.Getter;

/**
 * Business exception carrying an ErrorCode and optional details.
 * Thrown by service layer, caught by GlobalExceptionHandler.
 */
@Getter
public class ServiceException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object details;

    public ServiceException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.details = null;
    }

    public ServiceException(ErrorCode errorCode, Object details) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.details = details;
    }

    public ServiceException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ServiceException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
        this.details = null;
    }
}
