"""
Tests for dpp.common.health — Health endpoint.

Feature: dynamic-pricing-platform
Validates: R19.1, R19.2
"""

import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI

from common.health import health_router


@pytest.fixture
def app():
    app = FastAPI()
    app.include_router(health_router)
    return app


@pytest.mark.asyncio
async def test_health_returns_200(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "healthy"
