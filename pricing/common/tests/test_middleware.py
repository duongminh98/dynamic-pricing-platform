"""Tests for common.middleware — setup_common_middleware integration.

Feature: dynamic-pricing-platform
Validates: R19.1, R19.3, R19.5, R21.2
"""
import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI

from common.middleware import setup_common_middleware
from common.correlation import HEADER_NAME


@pytest.fixture
def app():
    app = FastAPI()
    setup_common_middleware(app)

    @app.get("/ok")
    async def ok():
        return {"status": "ok"}

    return app


@pytest.mark.asyncio
async def test_correlation_id_added(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/ok")
    assert resp.status_code == 200
    assert HEADER_NAME in resp.headers


@pytest.mark.asyncio
async def test_health_endpoint_registered(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "healthy"


@pytest.mark.asyncio
async def test_metrics_endpoint_registered(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/metrics")
    assert resp.status_code == 200
