"""Tests for app.main and app.routers — app construction and router registration.

Feature: dynamic-pricing-platform
Validates: R19.x, R21.2 (app wiring)
"""
import pytest
from httpx import AsyncClient, ASGITransport

from app.main import app


@pytest.mark.asyncio
async def test_app_has_routes():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        # Health route registered
        health_resp = await client.get("/health")
        assert health_resp.status_code == 200
        # Metrics route registered
        metrics_resp = await client.get("/metrics")
        assert metrics_resp.status_code == 200
        # Quote route registered (POST should not return 404/405)
        quote_resp = await client.post("/pricing/quote", json={"product_id": "x", "profile": {}})
        assert quote_resp.status_code != 404
        assert quote_resp.status_code != 405


@pytest.mark.asyncio
async def test_app_health_works():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "healthy"


@pytest.mark.asyncio
async def test_app_metrics_works():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/metrics")
    assert resp.status_code == 200
