"""
Convenience function to set up all common middleware on a FastAPI app.

Usage:
    from common import setup_common_middleware
    app = FastAPI(...)
    setup_common_middleware(app)

This registers:
  - Correlation-ID middleware (R19.5, R19.6)
  - Structured error handlers (R19.3, R18.4)
  - /health endpoint (R19.1)
  - Prometheus /metrics + middleware (R21.2)

Requirements: R19.1, R19.3, R19.5, R19.6, R21.2
"""

from fastapi import FastAPI

from .correlation import CorrelationIdMiddleware
from .errors import setup_exception_handlers
from .health import health_router
from .metrics import setup_metrics


def setup_common_middleware(app: FastAPI) -> None:
    """
    Register all cross-cutting common middleware and routes on a FastAPI app.

    Call this once after creating the FastAPI instance, before including
    business routers.
    """
    # 1. Correlation-ID middleware (outermost — runs first)
    app.add_middleware(CorrelationIdMiddleware)

    # 2. Prometheus metrics middleware + /metrics endpoint
    setup_metrics(app)

    # 3. Structured error handlers
    setup_exception_handlers(app)

    # 4. Health endpoint
    app.include_router(health_router)
