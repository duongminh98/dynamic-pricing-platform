"""Tests for app.routers.admin — admin endpoints with SQLite in-memory DB.

Feature: dynamic-pricing-platform
Validates: R37.4, R37.5, R37.7
"""
import base64
import json
import datetime
import uuid

import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import Depends, FastAPI
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, get_db, ModelVersion, ChampionAssignment, AuditTrail, ModelDriftFlag
from app.routers import admin
from common.errors import setup_exception_handlers


def make_token(roles):
    header = base64.urlsafe_b64encode(json.dumps({"alg": "RS256"}).encode()).decode().rstrip("=")
    payload = base64.urlsafe_b64encode(json.dumps({"realm_access": {"roles": roles}}).encode()).decode().rstrip("=")
    return header + "." + payload + ".sig"


def auth_header(roles):
    return {"Authorization": "Bearer " + make_token(roles)}


@pytest.fixture
def db_session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    session = Session()
    yield session
    session.close()


@pytest.fixture
def app(db_session):
    app = FastAPI()
    setup_exception_handlers(app)
    app.dependency_overrides[get_db] = lambda: db_session
    app.include_router(admin.router)
    app.include_router(admin.models_router)
    return app


def _insert_model(db, line="health", algorithm="LightGBM", gini=0.75, monotonic=True):
    mv = ModelVersion(
        model_version_id=str(uuid.uuid4()),
        line=line,
        algorithm=algorithm,
        gini=gini,
        rmse=100.0,
        mae=50.0,
        deviance=200.0,
        trained_at=datetime.datetime.now(datetime.timezone.utc),
        dataset_desc="test dataset",
        monotonic_applied=monotonic,
    )
    db.add(mv)
    db.commit()
    return mv


def _insert_champion(db, line, model_version_id):
    db.add(ChampionAssignment(
        assignment_id=str(uuid.uuid4()),
        line=line,
        model_version_id=model_version_id,
        is_current=True,
        created_at=datetime.datetime.now(datetime.timezone.utc),
    ))
    db.commit()


@pytest.mark.asyncio
async def test_list_models_admin_only(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/models")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_list_models_returns_all(app, db_session):
    _insert_model(db_session, line="health", gini=0.70)
    _insert_model(db_session, line="car", gini=0.65)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/models", headers=auth_header(["Administrator"]))
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 2


@pytest.mark.asyncio
async def test_promote_champion_success(app, db_session):
    old = _insert_model(db_session, line="health", gini=0.70)
    _insert_champion(db_session, "health", old.model_version_id)
    new = _insert_model(db_session, line="health", gini=0.75)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/admin/champion/promote",
            json={"line": "health", "model_version_id": new.model_version_id},
            headers=auth_header(["Administrator"]),
        )
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "success"
    assert body["promoted"] is True
    assert body["champion"] == new.model_version_id


@pytest.mark.asyncio
async def test_promote_champion_gini_not_improved(app, db_session):
    old = _insert_model(db_session, line="health", gini=0.75)
    _insert_champion(db_session, "health", old.model_version_id)
    new = _insert_model(db_session, line="health", gini=0.70)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/admin/champion/promote",
            json={"line": "health", "model_version_id": new.model_version_id},
            headers=auth_header(["Administrator"]),
        )
    assert resp.status_code == 200
    body = resp.json()
    assert body["promoted"] is False


@pytest.mark.asyncio
async def test_promote_champion_monotonic_not_applied(app, db_session):
    old = _insert_model(db_session, line="health", gini=0.70, monotonic=True)
    _insert_champion(db_session, "health", old.model_version_id)
    new = _insert_model(db_session, line="health", gini=0.80, monotonic=False)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/admin/champion/promote",
            json={"line": "health", "model_version_id": new.model_version_id},
            headers=auth_header(["Administrator"]),
        )
    assert resp.status_code == 200
    body = resp.json()
    assert body["promoted"] is False


@pytest.mark.asyncio
async def test_promote_champion_requires_admin(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/admin/champion/promote",
            json={"line": "health", "model_version_id": "abc"},
        )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_rollback_champion_success(app, db_session):
    old = _insert_model(db_session, line="health", gini=0.70)
    _insert_champion(db_session, "health", old.model_version_id)
    new = _insert_model(db_session, line="health", gini=0.75)
    # Promote new first
    db_session.query(ChampionAssignment).filter(
        ChampionAssignment.line == "health",
        ChampionAssignment.is_current.is_(True),
    ).update({"is_current": False})
    _insert_champion(db_session, "health", new.model_version_id)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/admin/champion/rollback",
            json={"line": "health"},
            headers=auth_header(["Administrator"]),
        )
    assert resp.status_code == 200
    body = resp.json()
    assert body["rolled_back"] is True
    assert body["champion"] == old.model_version_id


@pytest.mark.asyncio
async def test_rollback_champion_requires_admin(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/admin/champion/rollback",
            json={"line": "health"},
        )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_get_drift_status_empty(app, db_session):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/drift", headers=auth_header(["Administrator"]))
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 6
    for entry in body:
        assert entry["needs_recalibration"] is False
        assert entry["metrics"] == []


@pytest.mark.asyncio
async def test_get_drift_status_with_flags(app, db_session):
    now = datetime.datetime.now(datetime.timezone.utc)
    db_session.add(ModelDriftFlag(
        flag_id=str(uuid.uuid4()),
        line="health",
        metric="psi",
        value=0.15,
        threshold=0.1,
        needs_recalibration=True,
        computed_at=now,
    ))
    db_session.commit()

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/drift", headers=auth_header(["Administrator"]))
    assert resp.status_code == 200
    body = resp.json()
    health_entry = next(e for e in body if e["line"] == "health")
    assert health_entry["needs_recalibration"] is True
    assert "psi" in health_entry["metrics"]
    assert health_entry["metrics"]["psi"]["value"] == 0.15


@pytest.mark.asyncio
async def test_get_drift_requires_admin(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/drift")
    assert resp.status_code == 401
