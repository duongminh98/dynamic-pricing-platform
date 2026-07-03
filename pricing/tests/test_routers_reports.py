"""Tests for app.routers.reports — validation and fairness report endpoints.

Feature: dynamic-pricing-platform
Validates: R20, R13
"""
import json
import pathlib
import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI

from app.routers import reports
from common.errors import setup_exception_handlers
import app.config as config


def make_token(roles):
    import base64
    header = base64.urlsafe_b64encode(json.dumps({"alg": "RS256"}).encode()).decode().rstrip("=")
    payload = base64.urlsafe_b64encode(json.dumps({"realm_access": {"roles": roles}}).encode()).decode().rstrip("=")
    return header + "." + payload + ".sig"


def auth_header(roles):
    return {
        "X-Authenticated-User-Sub": "admin-user-123",
        "X-Authenticated-User-Roles": ",".join(roles),
        "X-Authenticated-User-Issuer": "http://localhost:8080/realms/dynamic-pricing",
        "X-Authenticated-Client-Id": "mini-app",
    }


@pytest.fixture
def app():
    app = FastAPI()
    setup_exception_handlers(app)
    app.include_router(reports.router)
    return app


@pytest.fixture
def enabled_app():
    app = FastAPI()
    setup_exception_handlers(app)
    app.include_router(reports.router)
    saved = config.VALIDATION_ENDPOINTS_ENABLED
    config.VALIDATION_ENDPOINTS_ENABLED = True
    yield app
    config.VALIDATION_ENDPOINTS_ENABLED = saved


@pytest.mark.asyncio
async def test_validation_disabled_returns_404(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/validation/health", headers=auth_header(["Administrator"]))
    assert resp.status_code == 404
    assert resp.json()["error_code"] == "VALIDATION_REPORT_UNAVAILABLE"


@pytest.mark.asyncio
async def test_fairness_disabled_returns_404(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/fairness/health", headers=auth_header(["Administrator"]))
    assert resp.status_code == 404
    assert resp.json()["error_code"] == "FAIRNESS_REPORT_UNAVAILABLE"


@pytest.mark.asyncio
async def test_validation_requires_admin(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/validation/health")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_fairness_requires_admin(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/fairness/health")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_validation_enabled_but_file_missing_returns_404(enabled_app):
    transport = ASGITransport(app=enabled_app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/validation/nonexistent_line", headers=auth_header(["Administrator"]))
    assert resp.status_code == 404
    assert resp.json()["error_code"] == "VALIDATION_REPORT_UNAVAILABLE"


@pytest.mark.asyncio
async def test_fairness_enabled_file_missing_returns_404(enabled_app):
    transport = ASGITransport(app=enabled_app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/fairness/nonexistent_line", headers=auth_header(["Administrator"]))
    assert resp.status_code == 404
    assert resp.json()["error_code"] == "FAIRNESS_REPORT_UNAVAILABLE"


@pytest.mark.asyncio
async def test_fairness_enabled_returns_file_when_exists(enabled_app, tmp_path, monkeypatch):
    report_data = {
        "gender_split": {"male": 0.6, "female": 0.4},
        "age_groups": {"18-25": 0.3},
        "requires_review": True,
    }
    dummy_file = tmp_path / "health_fairness.json"
    dummy_file.write_text(json.dumps(report_data))
    monkeypatch.setattr(reports, "REPORTS_DIR", tmp_path)

    transport = ASGITransport(app=enabled_app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/fairness/health", headers=auth_header(["Administrator"]))
    assert resp.status_code == 200
    body = resp.json()
    assert body["gender_split"]["male"] == 0.6
    assert body["requires_review"] is True


@pytest.mark.asyncio
async def test_validation_enabled_returns_file_when_exists(enabled_app, tmp_path, monkeypatch):
    report_data = {"gini": 0.72, "rmse": 1200}
    dummy_file = tmp_path / "health_validation.json"
    dummy_file.write_text(json.dumps(report_data))
    monkeypatch.setattr(reports, "REPORTS_DIR", tmp_path)

    transport = ASGITransport(app=enabled_app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/validation/health", headers=auth_header(["Administrator"]))
    assert resp.status_code == 200
    body = resp.json()
    assert body["gini"] == 0.72
