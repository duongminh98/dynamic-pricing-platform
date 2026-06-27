"""Tests for quote ownership and optional-auth (spec: Quote ownership).

Verifies:
- Customer (JWT sub=X) POST quote → stored customer_id=X
- Internal (no JWT) POST quote → stored customer_id="internal"
- Customer X GET own quote → 200
- Customer X GET quote of Y → 404
- Customer GET quote "internal" → 404
- Internal (no JWT) GET any quote → 200
- Malformed token → treated as internal (None subject)
- No more "anonymous" customer_id
"""
from __future__ import annotations

import base64
import json
import datetime
from unittest.mock import patch, MagicMock

import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, get_db, Quote
from app.routers import quote as quote_router
from common.errors import setup_exception_handlers


def _make_jwt(sub: str | None = None) -> str:
    """Create a fake JWT with the given sub claim (signature is irrelevant —
    pricing only decodes payload, Kong verifies signature in prod)."""
    header = base64.urlsafe_b64encode(json.dumps({"alg": "none", "typ": "JWT"}).encode()).decode().rstrip("=")
    payload_dict = {}
    if sub is not None:
        payload_dict["sub"] = sub
    payload = base64.urlsafe_b64encode(json.dumps(payload_dict).encode()).decode().rstrip("=")
    return f"{header}.{payload}.signature"


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
    app.include_router(quote_router.router)
    return app


VALID_PROFILE = {
    "age": 30,
    "gender": "Male",
    "province": "Ha Noi",
    "region": "Red River Delta",
    "urban_tier": "tier1",
    "occupation": "engineer",
    "income_level": "middle",
    "marital_status": "single",
    "line_attributes": {},
}


def _mock_quote_result():
    return {
        "quote_id": "test-quote-id",
        "line": "health",
        "product_id": "HEALTH_BASIC",
        "trip_duration_days": None,
        "coverage_amount_vnd": 100_000_000,
        "deductible_vnd": 0,
        "pure_premium_vnd": 500_000,
        "final_premium_vnd": 610_000,
        "currency": "VND",
        "expires_at": (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=7)).isoformat(),
        "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "explanation": {"available": False, "items": []},
        "model_version": "v1.0",
        "rate_version": "rv-test",
        "product_rate_version_id": "rv-test",
    }


# ── POST: customer_id from JWT sub ──

@pytest.mark.asyncio
async def test_post_quote_with_jwt_stores_sub_as_customer_id(app, db_session):
    token = _make_jwt(sub="customer-123")
    mock_result = _mock_quote_result()
    with patch("app.pricing_engine.engine.quote", return_value=mock_result):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post(
                "/pricing/quote",
                json={"product_id": "HEALTH_BASIC", "profile": VALID_PROFILE},
                headers={"Authorization": f"Bearer {token}"},
            )
    assert resp.status_code == 200
    db_row = db_session.query(Quote).first()
    assert db_row.customer_id == "customer-123"


@pytest.mark.asyncio
async def test_post_quote_without_jwt_stores_internal(app, db_session):
    mock_result = _mock_quote_result()
    with patch("app.pricing_engine.engine.quote", return_value=mock_result):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post(
                "/pricing/quote",
                json={"product_id": "HEALTH_BASIC", "profile": VALID_PROFILE},
            )
    assert resp.status_code == 200
    db_row = db_session.query(Quote).first()
    assert db_row.customer_id == "internal"


@pytest.mark.asyncio
async def test_post_quote_no_longer_anonymous(app, db_session):
    """Ensure customer_id is never 'anonymous'."""
    mock_result = _mock_quote_result()
    with patch("app.pricing_engine.engine.quote", return_value=mock_result):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post(
                "/pricing/quote",
                json={"product_id": "HEALTH_BASIC", "profile": VALID_PROFILE},
            )
    db_row = db_session.query(Quote).first()
    assert db_row.customer_id != "anonymous"


# ── GET: ownership enforcement ──

def _insert_quote(db_session, quote_id: str, customer_id: str):
    q = Quote(
        quote_id=quote_id,
        customer_id=customer_id,
        product_id="HEALTH_BASIC",
        line="health",
        trip_duration_days=None,
        coverage_amount_vnd=100_000_000,
        deductible_vnd=0,
        profile=VALID_PROFILE,
        pure_premium_vnd=500_000,
        final_premium_vnd=610_000,
        explanation={"available": False, "items": []},
        expires_at=datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=7),
        created_at=datetime.datetime.now(datetime.timezone.utc),
    )
    db_session.add(q)
    db_session.commit()
    return q


@pytest.mark.asyncio
async def test_customer_get_own_quote_200(app, db_session):
    _insert_quote(db_session, "q-owner", "customer-X")
    token = _make_jwt(sub="customer-X")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/quote/q-owner", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    assert resp.json()["quote_id"] == "q-owner"


@pytest.mark.asyncio
async def test_customer_get_other_quote_404(app, db_session):
    _insert_quote(db_session, "q-A", "customer-A")
    _insert_quote(db_session, "q-B", "customer-B")
    token = _make_jwt(sub="customer-A")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/quote/q-B", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_customer_get_internal_quote_404(app, db_session):
    _insert_quote(db_session, "q-internal", "internal")
    token = _make_jwt(sub="customer-Z")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/quote/q-internal", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_internal_get_any_quote_200(app, db_session):
    _insert_quote(db_session, "q-cust", "customer-A")
    _insert_quote(db_session, "q-int", "internal")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp1 = await client.get("/pricing/quote/q-cust")
        resp2 = await client.get("/pricing/quote/q-int")
    assert resp1.status_code == 200
    assert resp2.status_code == 200


@pytest.mark.asyncio
async def test_malformed_token_treated_as_internal(app, db_session):
    _insert_quote(db_session, "q-anyone", "customer-A")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get(
            "/pricing/quote/q-anyone",
            headers={"Authorization": "Bearer not.a.valid.jwt.but.long.enough"},
        )
    # Malformed token → optional_subject returns None → treated as internal → 200
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_garbage_bearer_treated_as_internal(app, db_session):
    _insert_quote(db_session, "q-test", "customer-A")
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get(
            "/pricing/quote/q-test",
            headers={"Authorization": "Bearer xyz"},
        )
    assert resp.status_code == 200


# ── optional_subject unit tests ──

def test_optional_subject_no_header():
    from common.auth import optional_subject
    req = MagicMock()
    req.headers = {}
    assert optional_subject(req) is None


def test_optional_subject_no_bearer():
    from common.auth import optional_subject
    req = MagicMock()
    req.headers = {"Authorization": "Basic abc"}
    assert optional_subject(req) is None


def test_optional_subject_valid_token():
    from common.auth import optional_subject
    token = _make_jwt(sub="user-42")
    req = MagicMock()
    req.headers = {"Authorization": f"Bearer {token}"}
    assert optional_subject(req) == "user-42"


def test_optional_subject_malformed_token():
    from common.auth import optional_subject
    req = MagicMock()
    req.headers = {"Authorization": "Bearer garbage"}
    assert optional_subject(req) is None


def test_optional_subject_token_without_sub():
    from common.auth import optional_subject
    token = _make_jwt(sub=None)
    req = MagicMock()
    req.headers = {"Authorization": f"Bearer {token}"}
    assert optional_subject(req) is None
