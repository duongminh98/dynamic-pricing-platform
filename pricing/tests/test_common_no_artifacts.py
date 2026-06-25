"""Tests for common modules — auth, correlation, metrics, errors, health, middleware.

Does not need model artifacts.
"""
from __future__ import annotations

import base64
import json
from unittest.mock import patch, MagicMock

import pytest
from fastapi import FastAPI, HTTPException
from httpx import AsyncClient, ASGITransport
from starlette.requests import Request

from common.auth import _decode_payload, realm_roles, require_role, require_administrator
from common.correlation import CorrelationIdMiddleware, get_correlation_id, HEADER_NAME
from common.errors import (
    ErrorCode, ServiceException, _build_error_body, setup_exception_handlers,
)
from common.health import health_router
from common.metrics import _metric, setup_metrics, REQUEST_DURATION, REQUEST_COUNT
from common.middleware import setup_common_middleware


# ── auth ──

def _make_jwt(payload: dict) -> str:
    header = base64.urlsafe_b64encode(b'{"alg":"none"}').rstrip(b'=').decode()
    body = base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b'=').decode()
    return f"{header}.{body}.signature"


def test_decode_payload_valid():
    token = _make_jwt({"sub": "user123", "realm_access": {"roles": ["user"]}})
    payload = _decode_payload(token)
    assert payload["sub"] == "user123"


def test_decode_payload_malformed():
    with pytest.raises(ServiceException) as exc_info:
        _decode_payload("not.a.token.with.too.many.parts")
    assert exc_info.value.error_code == ErrorCode.UNAUTHENTICATED


def test_decode_payload_two_parts():
    with pytest.raises(ServiceException) as exc_info:
        _decode_payload("only.one")
    assert exc_info.value.error_code == ErrorCode.UNAUTHENTICATED


def test_decode_payload_invalid_base64():
    with pytest.raises(ServiceException) as exc_info:
        _decode_payload("header.!!!@#$.signature")
    assert exc_info.value.error_code == ErrorCode.UNAUTHENTICATED


def test_realm_roles_with_roles():
    claims = {"realm_access": {"roles": ["user", "admin"]}}
    assert realm_roles(claims) == ["user", "admin"]


def test_realm_roles_empty():
    claims = {}
    assert realm_roles(claims) == []


def test_realm_roles_none():
    claims = {"realm_access": None}
    assert realm_roles(claims) == []


def test_realm_roles_no_roles_key():
    claims = {"realm_access": {}}
    assert realm_roles(claims) == []


@pytest.mark.asyncio
async def test_require_role_missing_token():
    dep = require_role("Administrator")
    request = MagicMock()
    request.headers = {}
    with pytest.raises(ServiceException) as exc_info:
        await dep(request, None)
    assert exc_info.value.error_code == ErrorCode.UNAUTHENTICATED


@pytest.mark.asyncio
async def test_require_role_missing_role():
    dep = require_role("Administrator")
    token = _make_jwt({"realm_access": {"roles": ["user"]}})
    request = MagicMock()
    request.headers = {}
    creds = MagicMock()
    creds.credentials = token
    with pytest.raises(ServiceException) as exc_info:
        await dep(request, creds)
    assert exc_info.value.error_code == ErrorCode.FORBIDDEN_RESOURCE


@pytest.mark.asyncio
async def test_require_role_success():
    dep = require_role("Administrator")
    token = _make_jwt({"realm_access": {"roles": ["Administrator"]}})
    request = MagicMock()
    request.headers = {}
    creds = MagicMock()
    creds.credentials = token
    claims = await dep(request, creds)
    assert "Administrator" in claims["realm_access"]["roles"]


# ── errors ──

def test_error_code_properties():
    assert ErrorCode.QUOTE_EXPIRED.code == "QUOTE_EXPIRED"
    assert ErrorCode.QUOTE_EXPIRED.http_status == 409
    assert "expired" in ErrorCode.QUOTE_EXPIRED.default_message.lower()


def test_service_exception_default_message():
    exc = ServiceException(ErrorCode.QUOTE_EXPIRED)
    assert exc.message == ErrorCode.QUOTE_EXPIRED.default_message


def test_service_exception_custom_message():
    exc = ServiceException(ErrorCode.QUOTE_EXPIRED, "Custom message")
    assert exc.message == "Custom message"


def test_service_exception_with_details():
    exc = ServiceException(ErrorCode.MISSING_FEATURES, details={"missing": ["age"]})
    assert exc.details == {"missing": ["age"]}


def test_build_error_body():
    body = _build_error_body(ErrorCode.QUOTE_EXPIRED, "corr-123", {"key": "val"})
    assert body["error_code"] == "QUOTE_EXPIRED"
    assert body["correlation_id"] == "corr-123"
    assert body["details"] == {"key": "val"}


@pytest.mark.asyncio
async def test_setup_exception_handlers_service_exception():
    app = FastAPI()
    setup_exception_handlers(app)

    @app.get("/raise-service")
    async def raise_service():
        raise ServiceException(ErrorCode.QUOTE_EXPIRED)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/raise-service")
    assert resp.status_code == 409
    assert resp.json()["error_code"] == "QUOTE_EXPIRED"


@pytest.mark.asyncio
async def test_setup_exception_handlers_http_exception():
    app = FastAPI()
    setup_exception_handlers(app)

    @app.get("/raise-404")
    async def raise_404():
        raise HTTPException(status_code=404)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/raise-404")
    assert resp.status_code == 404
    assert resp.json()["error_code"] == "ROUTE_NOT_FOUND"


@pytest.mark.asyncio
async def test_setup_exception_handlers_generic_exception():
    app = FastAPI()
    setup_exception_handlers(app)

    @app.get("/raise-generic")
    async def raise_generic():
        raise RuntimeError("Something went wrong")

    transport = ASGITransport(app=app, raise_app_exceptions=False)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/raise-generic")
    assert resp.status_code == 500
    assert resp.json()["error_code"] == "INTERNAL_ERROR"


@pytest.mark.asyncio
async def test_setup_exception_handlers_value_error():
    app = FastAPI()
    setup_exception_handlers(app)

    @app.get("/raise-value-error")
    async def raise_value_error():
        raise ValueError("Bad input")

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/raise-value-error")
    assert resp.status_code == 400
    assert resp.json()["error_code"] == "BAD_REQUEST"


# ── correlation ──

def test_get_correlation_id_default():
    assert get_correlation_id() == ""


@pytest.mark.asyncio
async def test_correlation_middleware_generates_id():
    app = FastAPI()
    app.add_middleware(CorrelationIdMiddleware)

    @app.get("/test")
    async def test_endpoint():
        return {"ok": True}

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/test")
    assert resp.status_code == 200
    assert HEADER_NAME in resp.headers
    assert resp.headers[HEADER_NAME] != ""


@pytest.mark.asyncio
async def test_correlation_middleware_preserves_existing_id():
    app = FastAPI()
    app.add_middleware(CorrelationIdMiddleware)

    @app.get("/test")
    async def test_endpoint():
        return {"ok": True}

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/test", headers={HEADER_NAME: "my-correlation-id"})
    assert resp.headers[HEADER_NAME] == "my-correlation-id"


# ── health ──

@pytest.mark.asyncio
async def test_health_endpoint():
    app = FastAPI()
    app.include_router(health_router)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "healthy"


# ── metrics ──

def test_metric_creates_new():
    from prometheus_client import Counter
    c = _metric(Counter, "test_counter_unique_xyz", "test counter")
    assert c is not None


def test_metric_reuses_existing():
    from prometheus_client import Counter
    c1 = _metric(Counter, "test_counter_reuse_xyz", "test counter")
    c2 = _metric(Counter, "test_counter_reuse_xyz", "test counter")
    assert c1 is c2


@pytest.mark.asyncio
async def test_setup_metrics_endpoint():
    app = FastAPI()
    setup_metrics(app)

    @app.get("/dummy")
    async def dummy():
        return {"ok": True}

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/metrics")
    assert resp.status_code == 200
    assert "text/plain" in resp.headers.get("content-type", "")


# ── middleware setup ──

@pytest.mark.asyncio
async def test_setup_common_middleware():
    app = FastAPI()
    setup_common_middleware(app)

    @app.get("/test")
    async def test_endpoint():
        return {"ok": True}

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        health_resp = await client.get("/health")
        corr_resp = await client.get("/test")

    assert health_resp.status_code == 200
    assert corr_resp.status_code == 200
    assert HEADER_NAME in corr_resp.headers
