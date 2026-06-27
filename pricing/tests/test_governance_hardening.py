"""T5-T11: Admin Model Governance Hardening tests.

Validates: real audit actor, promote reject reasons, models enrichment,
champion is_champion per line.
"""
import base64
import json
import datetime
import uuid

import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, get_db, ModelVersion, ChampionAssignment, AuditTrail, EventOutbox
from app.routers import admin
from common.errors import setup_exception_handlers


def make_token(roles, sub="admin-user-123"):
    header = base64.urlsafe_b64encode(json.dumps({"alg": "RS256"}).encode()).decode().rstrip("=")
    payload = base64.urlsafe_b64encode(
        json.dumps({"realm_access": {"roles": roles}, "sub": sub}).encode()
    ).decode().rstrip("=")
    return header + "." + payload + ".sig"


def auth_header(roles, sub="admin-user-123"):
    return {"Authorization": "Bearer " + make_token(roles, sub)}


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


# ── T5: Promote by admin X → audit actor = X ──

@pytest.mark.asyncio
async def test_t5_promote_audit_actor_is_real_subject(app, db_session):
    old = _insert_model(db_session, line="health", gini=0.70)
    _insert_champion(db_session, "health", old.model_version_id)
    new = _insert_model(db_session, line="health", gini=0.75)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/admin/champion/promote",
            json={"line": "health", "model_version_id": new.model_version_id},
            headers=auth_header(["Administrator"], sub="admin-alice"),
        )
    assert resp.status_code == 200
    assert resp.json()["promoted"] is True

    audits = db_session.query(AuditTrail).filter(
        AuditTrail.event_type == "CHAMPION_CHANGE"
    ).all()
    assert len(audits) == 1
    assert audits[0].actor == "admin-alice"


# ── T6: Rollback → audit actor is real ──

@pytest.mark.asyncio
async def test_t6_rollback_audit_actor_is_real_subject(app, db_session):
    old = _insert_model(db_session, line="health", gini=0.70)
    _insert_champion(db_session, "health", old.model_version_id)
    new = _insert_model(db_session, line="health", gini=0.75)
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
            headers=auth_header(["Administrator"], sub="admin-bob"),
        )
    assert resp.status_code == 200
    assert resp.json()["rolled_back"] is True

    audits = db_session.query(AuditTrail).filter(
        AuditTrail.event_type == "CHAMPION_CHANGE",
        AuditTrail.actor == "admin-bob",
    ).all()
    assert len(audits) == 1


# ── T7: Promote gini not improved → 200 {promoted:false, reason: GINI_NOT_IMPROVED} ──

@pytest.mark.asyncio
async def test_t7_promote_gini_not_improved(app, db_session):
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
    assert body["reason"] == "GINI_NOT_IMPROVED"
    assert body["champion"] == old.model_version_id


# ── T8: Promote monotonic not applied (non-exempt) → 200 {promoted:false, reason: MONOTONIC_NOT_APPLIED} ──

@pytest.mark.asyncio
async def test_t8_promote_monotonic_not_applied(app, db_session):
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
    assert body["reason"] == "MONOTONIC_NOT_APPLIED"


# ── T9: Promote valid → 200 {promoted:true}, champion changes, audit + event ──

@pytest.mark.asyncio
async def test_t9_promote_valid_success(app, db_session):
    old = _insert_model(db_session, line="health", gini=0.70)
    _insert_champion(db_session, "health", old.model_version_id)
    new = _insert_model(db_session, line="health", gini=0.75)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/admin/champion/promote",
            json={"line": "health", "model_version_id": new.model_version_id},
            headers=auth_header(["Administrator"], sub="admin-carol"),
        )
    assert resp.status_code == 200
    body = resp.json()
    assert body["promoted"] is True
    assert body["champion"] == new.model_version_id

    # Audit trail recorded
    audits = db_session.query(AuditTrail).filter(
        AuditTrail.event_type == "CHAMPION_CHANGE",
        AuditTrail.actor == "admin-carol",
    ).all()
    assert len(audits) == 1

    # Event published
    events = db_session.query(EventOutbox).filter(
        EventOutbox.event_type == "ChampionPromoted"
    ).all()
    assert len(events) == 1

    # Champion assignment updated
    current = db_session.query(ChampionAssignment).filter(
        ChampionAssignment.line == "health",
        ChampionAssignment.is_current.is_(True),
    ).first()
    assert current.model_version_id == new.model_version_id


# ── T10: GET /pricing/models?line=car → only car, is_champion correct ──

@pytest.mark.asyncio
async def test_t10_models_filter_by_line_with_champion(app, db_session):
    car1 = _insert_model(db_session, line="car", gini=0.65)
    _insert_champion(db_session, "car", car1.model_version_id)
    _insert_model(db_session, line="health", gini=0.70)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/models?line=car", headers=auth_header(["Administrator"]))
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 1
    assert body[0]["line"] == "car"
    assert body[0]["is_champion"] is True


# ── T11: GET /pricing/models → each line has exactly one is_champion=true ──

@pytest.mark.asyncio
async def test_t11_models_each_line_one_champion(app, db_session):
    health1 = _insert_model(db_session, line="health", gini=0.70)
    _insert_champion(db_session, "health", health1.model_version_id)
    car1 = _insert_model(db_session, line="car", gini=0.65)
    _insert_champion(db_session, "car", car1.model_version_id)
    _insert_model(db_session, line="health", gini=0.60)
    _insert_model(db_session, line="car", gini=0.55)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/models", headers=auth_header(["Administrator"]))
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 4

    champions = [m for m in body if m["is_champion"]]
    assert len(champions) == 2
    champion_lines = {m["line"] for m in champions}
    assert champion_lines == {"health", "car"}
