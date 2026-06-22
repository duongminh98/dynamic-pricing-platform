"""
DPP Common — Cross-cutting utilities for Pricing_Service (FastAPI).

Provides:
  - Correlation-ID middleware (R19.5, R19.6)
  - Structured error handler matching design §7.1 (R19.3, R18.4)
  - /health endpoint (R19.1)
  - Prometheus /metrics (R21.2)
"""

from .correlation import CorrelationIdMiddleware, get_correlation_id, HEADER_NAME
from .errors import ErrorCode, ServiceException, setup_exception_handlers
from .health import health_router
from .metrics import setup_metrics
from .middleware import setup_common_middleware

__all__ = [
    "CorrelationIdMiddleware",
    "get_correlation_id",
    "HEADER_NAME",
    "ErrorCode",
    "ServiceException",
    "setup_exception_handlers",
    "health_router",
    "setup_metrics",
    "setup_common_middleware",
]
