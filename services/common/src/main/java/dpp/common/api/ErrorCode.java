package dpp.common.api;

import lombok.Getter;

/**
 * Canonical error codes from design §7.2.
 * Each code maps to a default HTTP status and human-readable message.
 */
@Getter
public enum ErrorCode {

    // ── Customer (R1) ──
    EMAIL_ALREADY_USED(409, "Email already in use"),
    INVALID_EMAIL_FORMAT(400, "Invalid email format"),
    INVALID_PASSWORD_LENGTH(400, "Invalid password length"),
    ACCOUNT_LOCKED(423, "Account is locked"),

    // ── Profile (R2) ──
    PROFILE_FIELD_OUT_OF_RANGE(400, "Field value out of allowed range"),
    INVALID_CATEGORICAL_VALUE(400, "Invalid categorical value"),
    MISSING_REQUIRED_FIELDS(400, "Missing required fields"),

    // ── Pricing (R4, R5, R11, R12) ──
    MISSING_FEATURES(400, "Missing input features"),
    UNSUPPORTED_LINE(400, "Unsupported product line"),
    MISSING_CHAMPION(400, "No champion model configured for line"),
    CHALLENGER_NOT_CONFIGURED(409, "Challenger not configured for line"),

    // ── Order (R6) ──
    QUOTE_EXPIRED(409, "Quote has expired"),
    QUOTE_ALREADY_USED(409, "Quote has already been used"),
    UNDERWRITING_NOT_ELIGIBLE(409, "Profile not eligible for underwriting"),

    // ── Billing (R33) ──
    PAYMENT_FAILED(402, "Payment failed"),

    // ── Authorization (R18) ──
    FORBIDDEN_RESOURCE(403, "Access denied to resource"),

    // ── Policy lifecycle (R22–R25) ──
    POLICY_NOT_MODIFIABLE(409, "Policy cannot be modified"),
    ENDORSEMENT_DATE_OUT_OF_RANGE(400, "Endorsement date out of coverage range"),

    // ── Claims (R27, R28) ──
    INVALID_CLAIM_TRANSITION(409, "Invalid claim status transition"),
    OCCURRENCE_OUT_OF_COVERAGE(400, "Occurrence date outside coverage period"),

    // ── Overload (R17.5) ──
    SERVICE_OVERLOADED(503, "Service overloaded"),

    // ── Gateway (R9) ──
    ROUTE_NOT_FOUND(404, "Route not found"),
    UNAUTHENTICATED(401, "Unauthenticated"),
    SERVICE_UNAVAILABLE(503, "Service unavailable"),

    // ── Pricing validation (R20) ──
    VALIDATION_REPORT_UNAVAILABLE(404, "Validation report unavailable"),

    // ── Generic ──
    INTERNAL_ERROR(500, "Internal server error"),
    BAD_REQUEST(400, "Bad request");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return name();
    }
}
