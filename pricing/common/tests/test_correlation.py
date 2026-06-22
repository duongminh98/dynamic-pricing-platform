"""
Tests for dpp.common.correlation — CorrelationIdMiddleware.

Feature: dynamic-pricing-platform
Validates: R19.5, R19.6
"""

import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI

from common.correlation import CorrelationIdMiddleware, HEADER_NAME, get_correlation_id


@pytest.fixture
def app():
    app = FastAPI()
    app.add_middleware(CorrelationIdMiddleware)

    @app.get("/test")
    async def test_endpoint():
        return {"correlation_id": get_correlation_id()}

    return app


@pytest.mark.asyncio
async def test_generate_correlation_id_when_missing(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/test")

    assert response.status_code == 200
    assert HEADER_NAME in response.headers
    cid = response.headers[HEADER_NAME]
    assert len(cid) > 0
    # Should be a valid UUID
    import uuid
    uuid.UUID(cid)  # raises if invalid


@pytest.mark.asyncio
async def test_use_existing_correlation_id(app):
    existing_cid = "test-correlation-123"
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/test", headers={HEADER_NAME: existing_cid})

    assert response.headers[HEADER_NAME] == existing_cid
    body = response.json()
    assert body["correlation_id"] == existing_cid


@pytest.mark.asyncio
async def test_correlation_id_available_in_handler(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/test")

    body = response.json()
    assert body["correlation_id"] == response.headers[HEADER_NAME]
