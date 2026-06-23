"""
Structured error handling for FastAPI matching design §7.1.

Error schema:
    {
        "error_code": "QUOTE_EXPIRED",
        "message": "Quote has expired",
        "correlation_id": "c1a2...",
        "details": {}
    }

No PII or secret is ever included in responses (R18.4, R19.3).

Requirements: R19.3, R18.4
"""

from __future__ import annotations

from enum import Enum
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .correlation import get_correlation_id, HEADER_NAME


# ── Error codes per design §7.2 ──────────────────────────────────


class ErrorCode(Enum):
    """Canonical error codes with HTTP status and default message."""

    # ── Customer (R1) ──
    EMAIL_ALREADY_USED = ("EMAIL_ALREADY_USED", 409, "Email already in use")
    INVALID_EMAIL_FORMAT = ("INVALID_EMAIL_FORMAT", 400, "Invalid email format")
    INVALID_PASSWORD_LENGTH = ("INVALID_PASSWORD_LENGTH", 400, "Invalid password length")
    ACCOUNT_LOCKED = ("ACCOUNT_LOCKED", 423, "Account is locked")

    # ── Profile (R2) ──
    PROFILE_FIELD_OUT_OF_RANGE = ("PROFILE_FIELD_OUT_OF_RANGE", 400, "Field value out of allowed range")
    INVALID_CATEGORICAL_VALUE = ("INVALID_CATEGORICAL_VALUE", 400, "Invalid categorical value")
    MISSING_REQUIRED_FIELDS = ("MISSING_REQUIRED_FIELDS", 400, "Missing required fields")

    # ── Pricing (R4, R5, R11, R12) ──
    MISSING_FEATURES = ("MISSING_FEATURES", 400, "Missing input features")
    UNSUPPORTED_LINE = ("UNSUPPORTED_LINE", 400, "Unsupported product line")
    MISSING_CHAMPION = ("MISSING_CHAMPION", 400, "No champion model configured for line")

    # ── Order (R6) ──
    QUOTE_EXPIRED = ("QUOTE_EXPIRED", 409, "Quote has expired")
    QUOTE_ALREADY_USED = ("QUOTE_ALREADY_USED", 409, "Quote has already been used")
    ORDER_NOT_APPROVED = ("ORDER_NOT_APPROVED", 409, "Order not approved")

    # ── Billing (R33) ──
    PAYMENT_FAILED = ("PAYMENT_FAILED", 402, "Payment failed")

    # ── Authorization (R18) ──
    FORBIDDEN_RESOURCE = ("FORBIDDEN_RESOURCE", 403, "Access denied to resource")

    # ── Policy lifecycle (R22-R25) ──
    POLICY_NOT_MODIFIABLE = ("POLICY_NOT_MODIFIABLE", 409, "Policy cannot be modified")
    ENDORSEMENT_DATE_OUT_OF_RANGE = ("ENDORSEMENT_DATE_OUT_OF_RANGE", 400, "Endorsement date out of coverage range")

    # ── Claims (R27, R28) ──
    INVALID_CLAIM_TRANSITION = ("INVALID_CLAIM_TRANSITION", 409, "Invalid claim status transition")
    OCCURRENCE_OUT_OF_COVERAGE = ("OCCURRENCE_OUT_OF_COVERAGE", 400, "Occurrence date outside coverage period")

    # ── Overload (R17.5) ──
    SERVICE_OVERLOADED = ("SERVICE_OVERLOADED", 503, "Service overloaded")

    # ── Gateway (R9) ──
    ROUTE_NOT_FOUND = ("ROUTE_NOT_FOUND", 404, "Route not found")
    UNAUTHENTICATED = ("UNAUTHENTICATED", 401, "Unauthenticated")
    SERVICE_UNAVAILABLE = ("SERVICE_UNAVAILABLE", 503, "Service unavailable")

    # ── Pricing validation (R20) ──
    VALIDATION_REPORT_UNAVAILABLE = ("VALIDATION_REPORT_UNAVAILABLE", 404, "Validation report unavailable")

    # ── Generic ──
    INTERNAL_ERROR = ("INTERNAL_ERROR", 500, "Internal server error")
    BAD_REQUEST = ("BAD_REQUEST", 400, "Bad request")

    def __init__(self, code: str, http_status: int, default_message: str):
        self._code = code
        self._http_status = http_status
        self._default_message = default_message

    @property
    def code(self) -> str:
        return self._code

    @property
    def http_status(self) -> int:
        return self._http_status

    @property
    def default_message(self) -> str:
        return self._default_message


# ── Exception class ───────────────────────────────────────────────


class ServiceException(Exception):
    """Business exception carrying an ErrorCode and optional details."""

    def __init__(self, error_code: ErrorCode, message: str | None = None, details: Any = None):
        self.error_code = error_code
        self.message = message or error_code.default_message
        self.details = details
        super().__init__(self.message)


# ── Response builder ──────────────────────────────────────────────


def _build_error_body(error_code: ErrorCode, correlation_id: str, details: Any = None) -> dict:
    return {
        "error_code": error_code.code,
        "message": error_code.default_message,
        "correlation_id": correlation_id,
        "details": details,
    }


# ── FastAPI exception handlers ────────────────────────────────────


def setup_exception_handlers(app: FastAPI) -> None:
    """Register structured exception handlers on a FastAPI app."""

    @app.exception_handler(ServiceException)
    async def service_exception_handler(request: Request, exc: ServiceException) -> JSONResponse:
        body = _build_error_body(exc.error_code, get_correlation_id(), exc.details)
        return JSONResponse(status_code=exc.error_code.http_status, content=body)

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        # Map common HTTP errors to our schema
        code_map = {
            401: ErrorCode.UNAUTHENTICATED,
            403: ErrorCode.FORBIDDEN_RESOURCE,
            404: ErrorCode.ROUTE_NOT_FOUND,
        }
        error_code = code_map.get(exc.status_code, ErrorCode.INTERNAL_ERROR)
        body = _build_error_body(error_code, get_correlation_id())
        return JSONResponse(status_code=exc.status_code, content=body)

    @app.exception_handler(Exception)
    async def generic_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        # Deliberately do NOT expose exception details (may contain PII/stack)
        body = _build_error_body(ErrorCode.INTERNAL_ERROR, get_correlation_id())
        return JSONResponse(status_code=500, content=body)

    @app.exception_handler(ValueError)
    async def value_error_handler(request: Request, exc: ValueError) -> JSONResponse:
        body = _build_error_body(ErrorCode.BAD_REQUEST, get_correlation_id(), {"reason": str(exc)})
        return JSONResponse(status_code=400, content=body)
