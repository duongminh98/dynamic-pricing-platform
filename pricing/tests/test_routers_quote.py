"""Tests for app.routers.quote â€” POST /quote and GET /quote/{quote_id}.

Feature: dynamic-pricing-platform
Validates: R5.1-R5.6, explanation persistence
"""
import pytest
import datetime
from httpx import AsyncClient, ASGITransport
from fastapi import FastAPI
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, get_db, Quote, CustomerRiskProfile, QuoteReadyProfile, EventOutbox
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
async def test_post_quote_without_profile_rebuilds_quote_ready_projection(app, db_session):
    projection_customer_id = quote_router.customer_id_from_subject("projection-user")
    db_session.add(CustomerRiskProfile(
        customer_id=projection_customer_id,
        profile_version=7,
        effective_at=datetime.datetime(2026, 7, 1, tzinfo=datetime.timezone.utc),
        common_risk_attributes={
            "age": 34,
            "gender": "Female",
            "province": "Ha Noi",
            "region": "Red River Delta",
            "urban_tier": "tier1",
            "occupation": "analyst",
            "income_level": "middle",
            "marital_status": "married",
        },
        line_risk_attributes={
            "health": {
                "smoker": False,
                "height_cm": 165,
                "weight_kg": 58,
                "bmi": 21.3,
                "coverage_amount_vnd": 400_000_000,
                "deductible_vnd": 2_000_000,
            }
        },
        last_event_id="profile-7",
        updated_at=datetime.datetime(2026, 7, 1, tzinfo=datetime.timezone.utc),
    ))
    db_session.commit()

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/pricing/quote",
            json={"product_id": "HEALTH_BASIC"},
            headers={"x-authenticated-user-sub": "projection-user"},
        )

    assert resp.status_code == 200
    quote_id = resp.json()["quote_id"]

    projection = db_session.query(QuoteReadyProfile).filter_by(
        customer_id=projection_customer_id,
        line="health",
    ).first()
    assert projection is not None
    assert projection.enriched_profile["profile_version"] == 7

    db_row = db_session.query(Quote).filter(Quote.quote_id == quote_id).first()
    assert db_row is not None
    assert db_row.profile["age"] == 34
    assert db_row.profile["profile_version"] == 7
    assert db_row.profile["claim_count_36m_prior"] == 0

    event = db_session.query(EventOutbox).filter_by(event_type="QuoteCreated").first()
    assert event is not None
    assert event.payload["quote_id"] == quote_id
    assert event.payload["customer_id"] == projection_customer_id


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

@pytest.mark.asyncio
async def test_post_quote_returns_reference_version_ids(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/pricing/quote",
            json={"product_id": "HEALTH_BASIC", "profile": VALID_PROFILE},
        )
    assert resp.status_code == 200
    body = resp.json()
    assert "geo_risk_version_id" in body
    assert "cost_index_version_id" in body

@pytest.mark.asyncio
async def test_get_quote_returns_reference_version_ids(app):
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
    assert "geo_risk_version_id" in body
    assert "cost_index_version_id" in body
