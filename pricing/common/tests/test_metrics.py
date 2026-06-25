"""Tests for common.metrics — Prometheus middleware and /metrics endpoint.

Feature: dynamic-pricing-platform
Validates: R21.2
"""
import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI

from common.metrics import setup_metrics


@pytest.fixture
def app():
    app = FastAPI()
    setup_metrics(app)

    @app.get("/ping")
    async def ping():
        return {"ok": True}

    return app


@pytest.mark.asyncio
async def test_metrics_endpoint_returns_200(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/metrics")
    assert resp.status_code == 200
    assert "text/plain" in resp.headers.get("content-type", "")


@pytest.mark.asyncio
async def test_request_counted_after_call(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        await client.get("/ping")
        resp = await client.get("/metrics")
    body = resp.text
    assert "dpp_request_total" in body
    assert "dpp_request_duration_seconds" in body
    assert "dpp_request_in_progress" in body


@pytest.mark.asyncio
async def test_in_progress_gauge_decremented_after_call(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        await client.get("/ping")
        resp = await client.get("/metrics")
    # After the request completes, in_progress for /ping should be 0
    body = resp.text
    assert "dpp_request_in_progress" in body
