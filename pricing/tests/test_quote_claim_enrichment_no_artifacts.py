import datetime

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, get_db, Quote
from app.routers import quote as quote_router
from common.errors import setup_exception_handlers


@pytest.fixture
def db_session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    session = Session()
    yield session
    session.close()


@pytest.fixture
def app(db_session, monkeypatch):
    app = FastAPI()
    setup_exception_handlers(app)
    app.dependency_overrides[get_db] = lambda: db_session
    app.include_router(quote_router.router)

    monkeypatch.setattr(quote_router, "get_line_for_product", lambda product_id: "health")
    monkeypatch.setattr(
        quote_router,
        "aggregate_claim_history",
        lambda db, customer_id, line: {
            "claim_count_12m_prior": 2,
            "claim_count_36m_prior": 4,
            "claim_count_lifetime_prior": 5,
            "total_incurred_36m_prior": 40_000_000.0,
            "avg_incurred_36m_prior": 10_000_000.0,
            "max_incurred_36m_prior": 20_000_000.0,
            "days_since_last_claim_prior": 15,
            "claim_severity_score_prior": 0.2,
        },
    )

    def fake_quote(db, product_id, profile):
        now = datetime.datetime.now(datetime.timezone.utc)
        return {
            "quote_id": "quote-enriched",
            "line": "health",
            "product_id": product_id,
            "coverage_amount_vnd": 100_000_000,
            "deductible_vnd": 0,
            "pure_premium_vnd": 1000,
            "final_premium_vnd": 3000,
            "currency": "VND",
            "expires_at": (now + datetime.timedelta(days=7)).isoformat(),
            "created_at": now.isoformat(),
            "explanation": {"available": False, "items": []},
        }

    monkeypatch.setattr("app.pricing_engine.engine.quote", fake_quote)
    # The router builds an audit snapshot via loader.required_columns +
    # features.feature_set_for_audit (imported inside the handler). Without model
    # artifacts these hit load_artifacts() and hard-fail, so stub them at source.
    monkeypatch.setattr("app.pricing_engine.loader.required_columns", lambda line: ["age"])
    monkeypatch.setattr(
        "app.pricing_engine.features.feature_set_for_audit",
        lambda line, product_id, profile, feature_names: {"age": profile.get("age")},
    )
    return app


@pytest.mark.asyncio
async def test_post_quote_enriches_and_overrides_client_claim_features(app, db_session):
    profile = {
        "age": 30,
        "gender": "Male",
        "province": "Ha Noi",
        "region": "Red River Delta",
        "urban_tier": "tier1",
        "occupation": "engineer",
        "income_level": "middle",
        "marital_status": "single",
        "claim_count_12m_prior": 999,
        "line_attributes": {"smoker": False},
    }

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/pricing/quote",
            headers={"x-authenticated-user-sub": "customer-1"},
            json={"product_id": "HEALTH_BASIC", "profile": profile},
        )

    assert resp.status_code == 200
    db_quote = db_session.query(Quote).filter(Quote.quote_id == "quote-enriched").first()
    assert db_quote is not None
    assert db_quote.customer_id == quote_router.customer_id_from_subject("customer-1")
    assert db_quote.profile["claim_count_12m_prior"] == 2
    assert db_quote.profile["claim_count_36m_prior"] == 4
    assert db_quote.profile["days_since_last_claim_prior"] == 15
