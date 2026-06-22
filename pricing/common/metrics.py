"""
Prometheus metrics setup for FastAPI.

Exposes /metrics endpoint with:
  - request duration histogram
  - request counter (total + by status)
  - in-progress gauge

Requirements: R21.2 (prometheus_client /metrics)
"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING

from prometheus_client import Counter, Histogram, Gauge, generate_latest, CONTENT_TYPE_LATEST
from starlette.requests import Request
from starlette.responses import Response

if TYPE_CHECKING:
    from fastapi import FastAPI

# ── Metric definitions ─────────────────────────────────────────────

REQUEST_DURATION = Histogram(
    "dpp_request_duration_seconds",
    "Request duration in seconds",
    labelnames=["method", "endpoint", "status_code"],
    buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0),
)

REQUEST_COUNT = Counter(
    "dpp_request_total",
    "Total request count",
    labelnames=["method", "endpoint", "status_code"],
)

REQUEST_IN_PROGRESS = Gauge(
    "dpp_request_in_progress",
    "Requests currently in progress",
    labelnames=["method", "endpoint"],
)


def setup_metrics(app: "FastAPI") -> None:
    """
    Register Prometheus metrics middleware and /metrics endpoint on a FastAPI app.

    Usage:
        from dpp.common.metrics import setup_metrics
        setup_metrics(app)
    """

    @app.middleware("http")
    async def prometheus_middleware(request: Request, call_next):
        method = request.method
        path = request.url.path

        REQUEST_IN_PROGRESS.labels(method=method, endpoint=path).inc()
        start = time.monotonic()
        try:
            response = await call_next(request)
            duration = time.monotonic() - start
            status = str(response.status_code)
            REQUEST_DURATION.labels(method=method, endpoint=path, status_code=status).observe(duration)
            REQUEST_COUNT.labels(method=method, endpoint=path, status_code=status).inc()
            return response
        finally:
            REQUEST_IN_PROGRESS.labels(method=method, endpoint=path).dec()

    @app.get("/metrics", include_in_schema=False, tags=["monitoring"])
    async def metrics_endpoint():
        """Expose Prometheus metrics."""
        return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)
