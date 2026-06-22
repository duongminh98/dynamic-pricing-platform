"""
Correlation-ID middleware for FastAPI.

Reads X-Correlation-Id from incoming request, generates UUID if missing,
stores in context var for logging/access, and echoes in response header.

Requirements: R19.5 (read/set X-Correlation-Id), R19.6 (propagate on outgoing).
"""

from contextvars import ContextVar
from uuid import uuid4

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

# Context var accessible from anywhere in the request lifecycle
_correlation_id: ContextVar[str] = ContextVar("correlation_id", default="")

HEADER_NAME = "X-Correlation-Id"


def get_correlation_id() -> str:
    """Return the current correlation ID (empty string if not set)."""
    return _correlation_id.get("")


class CorrelationIdMiddleware(BaseHTTPMiddleware):
    """
    ASGI middleware that:
    1. Reads X-Correlation-Id from request header, or generates a new UUID.
    2. Stores it in a context var for downstream code / logging.
    3. Echoes it back in the response header.
    """

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        correlation_id = request.headers.get(HEADER_NAME, "").strip()
        if not correlation_id:
            correlation_id = str(uuid4())

        _correlation_id.set(correlation_id)
        request.state.correlation_id = correlation_id

        response = await call_next(request)
        response.headers[HEADER_NAME] = correlation_id
        return response
