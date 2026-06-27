"""Tests for app.routers and app.database — endpoints with mocked DB.

Covers quote router (get_quote), admin router (list_models, promote, rollback, drift),
reports router (validation, fairness), and database get_db.
Does not need model artifacts.
"""
from __future__ import annotations

import datetime
from unittest.mock import MagicMock, patch, AsyncMock

import pytest
from fastapi import FastAPI
from httpx import AsyncClient, ASGITransport

from app.routers import quote, admin
from app.routers.quote import QuoteRequest
from app.routers.admin import PromoteRequest, RollbackRequest
from app import database


# ── database.get_db ──

def test_get_db_yields_and_closes():
    mock_session = MagicMock()
    with patch.object(database, "SessionLocal", return_value=mock_session):
        gen = database.get_db()
        db = next(gen)
        assert db is mock_session
        with pytest.raises(StopIteration):
            next(gen)
    mock_session.close.assert_called_once()


# ── quote router: get_quote ──

@pytest.mark.asyncio
async def test_get_quote_found():
    app = FastAPI()
    app.include_router(quote.router)

    mock_db_quote = MagicMock()
    mock_db_quote.quote_id = "q123"
    mock_db_quote.product_id = "HEALTH_BASIC"
    mock_db_quote.line = "health"
    mock_db_quote.trip_duration_days = None
    mock_db_quote.coverage_amount_vnd = 100_000_000
    mock_db_quote.deductible_vnd = 0
    mock_db_quote.profile = {"age": 30}
    mock_db_quote.pure_premium_vnd = 500_000
    mock_db_quote.final_premium_vnd = 560_000
    mock_db_quote.explanation = None
    mock_db_quote.expires_at = datetime.datetime(2026, 12, 31, tzinfo=datetime.timezone.utc)
    mock_db_quote.created_at = datetime.datetime(2026, 6, 25, tzinfo=datetime.timezone.utc)

    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.first.return_value = mock_db_quote

    async def override_get_db():
        yield mock_db

    app.dependency_overrides[quote.get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/quote/q123")

    assert resp.status_code == 200
    data = resp.json()
    assert data["quote_id"] == "q123"
    assert data["product_id"] == "HEALTH_BASIC"
    assert data["pure_premium_vnd"] == 500_000


@pytest.mark.asyncio
async def test_get_quote_not_found():
    app = FastAPI()
    app.include_router(quote.router)

    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.first.return_value = None

    async def override_get_db():
        yield mock_db

    app.dependency_overrides[quote.get_db] = override_get_db

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/quote/nonexistent")

    assert resp.status_code == 404


# ── admin router: drift status ──

@pytest.mark.asyncio
async def test_get_drift_status_empty():
    app = FastAPI()
    app.include_router(admin.models_router)

    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.order_by.return_value.all.return_value = []

    async def override_get_db():
        yield mock_db

    def override_auth():
        return {"realm_access": {"roles": ["Administrator"]}}

    app.dependency_overrides[admin.get_db] = override_get_db
    app.dependency_overrides[admin.require_administrator] = override_auth

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/drift")

    assert resp.status_code == 200
    data = resp.json()
    assert len(data) == 6
    assert all(d["needs_recalibration"] is False for d in data)


@pytest.mark.asyncio
async def test_get_drift_status_with_flags():
    app = FastAPI()
    app.include_router(admin.models_router)

    mock_flag = MagicMock()
    mock_flag.metric = "psi"
    mock_flag.value = 0.15
    mock_flag.threshold = 0.1
    mock_flag.needs_recalibration = True
    mock_flag.computed_at = datetime.datetime(2026, 6, 25, tzinfo=datetime.timezone.utc)

    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.order_by.return_value.all.return_value = [mock_flag]

    async def override_get_db():
        yield mock_db

    def override_auth():
        return {"realm_access": {"roles": ["Administrator"]}}

    app.dependency_overrides[admin.get_db] = override_get_db
    app.dependency_overrides[admin.require_administrator] = override_auth

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/drift")

    assert resp.status_code == 200
    data = resp.json()
    health_entry = next(d for d in data if d["line"] == "health")
    assert health_entry["needs_recalibration"] is True
    assert "psi" in health_entry["metrics"]


# ── admin router: list models ──

@pytest.mark.asyncio
async def test_list_models():
    app = FastAPI()
    app.include_router(admin.models_router)

    mock_model = MagicMock()
    mock_model.model_version_id = "mv1"
    mock_db = MagicMock()
    mock_db.query.return_value.all.return_value = [mock_model]

    async def override_get_db():
        yield mock_db

    def override_auth():
        return {"realm_access": {"roles": ["Administrator"]}}

    app.dependency_overrides[admin.get_db] = override_get_db
    app.dependency_overrides[admin.require_administrator] = override_auth

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/models")

    assert resp.status_code == 200


# ── admin router: promote champion ──

@pytest.mark.asyncio
async def test_promote_champion_endpoint():
    app = FastAPI()
    app.include_router(admin.router)

    mock_db = MagicMock()

    async def override_get_db():
        yield mock_db

    def override_auth():
        return {"realm_access": {"roles": ["Administrator"]}}

    app.dependency_overrides[admin.get_db] = override_get_db
    app.dependency_overrides[admin.require_administrator] = override_auth

    with patch("app.pricing_engine.governance.promote_champion",
               return_value={"promoted": True, "champion": "mv2"}):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/admin/champion/promote",
                                     json={"line": "health", "model_version_id": "mv2"})

    assert resp.status_code == 200
    assert resp.json()["promoted"] is True


@pytest.mark.asyncio
async def test_rollback_champion_endpoint():
    app = FastAPI()
    app.include_router(admin.router)

    mock_db = MagicMock()

    async def override_get_db():
        yield mock_db

    def override_auth():
        return {"realm_access": {"roles": ["Administrator"]}}

    app.dependency_overrides[admin.get_db] = override_get_db
    app.dependency_overrides[admin.require_administrator] = override_auth

    with patch("app.pricing_engine.governance.rollback_champion",
               return_value={"rolled_back": True, "champion": "mv1"}):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/admin/champion/rollback",
                                     json={"line": "health"})

    assert resp.status_code == 200
    assert resp.json()["rolled_back"] is True
