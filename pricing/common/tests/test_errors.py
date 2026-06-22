"""
Tests for dpp.common.errors — Structured error handling.

Feature: dynamic-pricing-platform
Validates: R19.3, R18.4
"""

import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI

from common.correlation import CorrelationIdMiddleware
from common.errors import ErrorCode, ServiceException, setup_exception_handlers, HEADER_NAME


@pytest.fixture
def app():
    app = FastAPI()
    app.add_middleware(CorrelationIdMiddleware)
    setup_exception_handlers(app)

    @app.get("/ok")
    async def ok_endpoint():
        return {"status": "ok"}

    @app.get("/business-error")
    async def business_error():
        raise ServiceException(ErrorCode.QUOTE_EXPIRED)

    @app.get("/business-error-with-details")
    async def business_error_details():
        raise ServiceException(ErrorCode.MISSING_FEATURES, details={"missing": ["age", "bmi"]})

    @app.get("/value-error")
    async def value_error_endpoint():
        raise ValueError("Invalid input")

    return app


@pytest.mark.asyncio
async def test_ok_response(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/ok")

    assert response.status_code == 200


@pytest.mark.asyncio
async def test_service_exception_returns_structured_error(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/business-error")

    assert response.status_code == 409
    body = response.json()
    assert body["error_code"] == "QUOTE_EXPIRED"
    assert "message" in body
    assert "correlation_id" in body
    # No PII in response
    assert "stack" not in body
    assert "trace" not in body


@pytest.mark.asyncio
async def test_service_exception_with_details(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/business-error-with-details")

    body = response.json()
    assert body["error_code"] == "MISSING_FEATURES"
    assert body["details"]["missing"] == ["age", "bmi"]


@pytest.mark.asyncio
async def test_value_error_returns_400(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/value-error")

    assert response.status_code == 400
    body = response.json()
    assert body["error_code"] == "BAD_REQUEST"


@pytest.mark.asyncio
async def test_correlation_id_in_error_response(app):
    test_cid = "test-cid-999"
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/business-error", headers={HEADER_NAME: test_cid})

    body = response.json()
    assert body["correlation_id"] == test_cid
