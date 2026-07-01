"""Tests for app.routers.quote — POST /quote and GET /quote/{quote_id}.

Feature: dynamic-pricing-platform
Validates: R5.1-R5.6, explanation persistence
"""
import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, get_db, Quote
from app.routers import quote as quote_router
from common.errors import setup_exception_handlers

from tests.conftest import skip_if_no_artifacts

pytestmark = skip_if_no_artifacts


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
    "line_attributes": {
        "smoker": False,
        "height_cm": 170,
        "weight_kg": 65,
        "bmi": 22.5,
        "coverage_amount_vnd": 500_000_000,
        "deductible_vnd": 1_000_000,
    },
}


@pytest.mark.asyncio
async def test_post_quote_returns_quote(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/pricing/quote",
            json={"product_id": "HEALTH_BASIC", "profile": VALID_PROFILE},
        )
    assert resp.status_code == 200
    body = resp.json()
    assert "quote_id" in body
    assert body["currency"] == "VND"
    assert body["pure_premium_vnd"] >= 0
    assert body["final_premium_vnd"] >= 0
    assert "explanation" in body
    assert "available" in body["explanation"]
    assert "items" in body["explanation"]


@pytest.mark.asyncio
async def test_post_quote_persists_explanation(app, db_session):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/pricing/quote",
            json={"product_id": "HEALTH_BASIC", "profile": VALID_PROFILE},
        )
    quote_id = resp.json()["quote_id"]

    db_row = db_session.query(Quote).filter(Quote.quote_id == quote_id).first()
    assert db_row is not None
    assert db_row.explanation is not None
    assert "available" in db_row.explanation


@pytest.mark.asyncio
async def test_get_quote_returns_explanation(app, db_session):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        post_resp = await client.post(
            "/pricing/quote",
            json={"product_id": "HEALTH_BASIC", "profile": VALID_PROFILE},
        )
    quote_id = post_resp.json()["quote_id"]

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        get_resp = await client.get(f"/pricing/quote/{quote_id}")
    assert get_resp.status_code == 200
    body = get_resp.json()
    assert body["quote_id"] == quote_id
    assert "explanation" in body
    assert "available" in body["explanation"]
    assert "items" in body["explanation"]


@pytest.mark.asyncio
async def test_quote_persists_and_returns_profile(app, db_session):
    """The raw rating profile must round-trip so order-service can use it as the
    endorsement re-rate base (R23.2/R23.8)."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        post_resp = await client.post(
            "/pricing/quote",
            json={"product_id": "HEALTH_BASIC", "profile": VALID_PROFILE},
        )
    quote_id = post_resp.json()["quote_id"]

    db_row = db_session.query(Quote).filter(Quote.quote_id == quote_id).first()
    assert db_row.profile is not None
    for key, value in VALID_PROFILE.items():
        assert db_row.profile[key] == value

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        get_resp = await client.get(f"/pricing/quote/{quote_id}")
    body = get_resp.json()
    assert body["profile"] is not None
    for key, value in VALID_PROFILE.items():
        assert body["profile"][key] == value


@pytest.mark.asyncio
async def test_get_quote_not_found(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/pricing/quote/nonexistent-id")
    assert resp.status_code == 404
    body = resp.json()
    assert body["error_code"] == "ROUTE_NOT_FOUND"


@pytest.mark.asyncio
async def test_post_quote_invalid_profile(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/pricing/quote",
            json={"product_id": "HEALTH_BASIC", "profile": {"age": -5}},
        )
    assert resp.status_code == 400
