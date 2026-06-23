"""Prometheus metrics setup for FastAPI.

Exposes /metrics endpoint with:
  - request duration histogram
  - request counter (total + by status)
  - in-progress gauge

The metric objects are created idempotently: when this module is imported
under more than one identity (e.g. ``common.metrics`` and
``pricing.common.metrics``) the prometheus default ``REGISTRY`` already holds
the collector, so we reuse it instead of raising "Duplicated timeseries".

Requirements: R21.2 (prometheus_client /metrics)
"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING

from prometheus_client import (
    Counter, Histogram, Gauge, REGISTRY,
    generate_latest, CONTENT_TYPE_LATEST,
)
from starlette.requests import Request
from starlette.responses import Response

if TYPE_CHECKING:
    from fastapi import FastAPI


def _metric(cls, name: str, documentation: str, **kwargs):
    """Create a prometheus metric, reusing an existing collector if present.

    prometheus_client raises ``ValueError`` on duplicate registration in the
    default ``REGISTRY``. When this module is imported under multiple package
    identities the module-level code re-runs, so we fall back to the already
    registered collector to keep registration idempotent.
    """
    try:
        return cls(name, documentation, **kwargs)
    except ValueError:
        existing = REGISTRY._names_to_collectors.get(name)
        if existing is not None:
            return existing
        raise


# -- Metric definitions (idempotent) ----------------------------------------

REQUEST_DURATION = _metric(
    Histogram,
    name="dpp_request_duration_seconds",
    documentation="Request duration in seconds",
    labelnames=["method", "endpoint", "status_code"],
    buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0),
)

REQUEST_COUNT = _metric(
    Counter,
    name="dpp_request_total",
    documentation="Total request count",
    labelnames=["method", "endpoint", "status_code"],
)

REQUEST_IN_PROGRESS = _metric(
    Gauge,
    name="dpp_request_in_progress",
    documentation="Requests currently in progress",
    labelnames=["method", "endpoint"],
)


def setup_metrics(app: "FastAPI") -> None:
    """Register Prometheus metrics middleware and /metrics endpoint."""

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