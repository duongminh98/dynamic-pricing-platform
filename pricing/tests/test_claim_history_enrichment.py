import datetime

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, ClaimOutcome
from app.services.claim_history import (
    aggregate_claim_history,
    enrich_profile_with_claim_history,
)


def _session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    return Session()


def _outcome(claim_id, customer_id, line, loss, settled_at):
    return ClaimOutcome(
        claim_id=claim_id,
        customer_id=customer_id,
        policy_id=f"policy-{claim_id}",
        quote_id=f"quote-{claim_id}",
        line=line,
        actual_loss_vnd=loss,
        settled_at=settled_at,
        recorded_at=settled_at,
    )


def test_aggregate_claim_history_filters_by_customer_line_and_as_of():
    db = _session()
    as_of = datetime.datetime(2026, 6, 30, tzinfo=datetime.timezone.utc)
    db.add_all([
        _outcome("recent", "customer-1", "health", 10_000_000, as_of - datetime.timedelta(days=20)),
        _outcome("old", "customer-1", "health", 30_000_000, as_of - datetime.timedelta(days=500)),
        _outcome("other-line", "customer-1", "car", 90_000_000, as_of - datetime.timedelta(days=10)),
        _outcome("other-customer", "customer-2", "health", 80_000_000, as_of - datetime.timedelta(days=10)),
        _outcome("future", "customer-1", "health", 50_000_000, as_of + datetime.timedelta(days=1)),
    ])
    db.commit()

    features = aggregate_claim_history(db, "customer-1", "health", as_of)

    assert features["claim_count_12m_prior"] == 1
    assert features["claim_count_36m_prior"] == 2
    assert features["claim_count_lifetime_prior"] == 2
    assert features["total_incurred_36m_prior"] == 40_000_000.0
    assert features["avg_incurred_36m_prior"] == 20_000_000.0
    assert features["max_incurred_36m_prior"] == 30_000_000.0
    assert features["days_since_last_claim_prior"] == 20
    assert features["claim_severity_score_prior"] == 0.3


def test_enrich_profile_overrides_client_claim_features():
    profile = {
        "age": 30,
        "claim_count_12m_prior": 999,
        "line_attributes": {"smoker": False},
    }
    features = {
        "claim_count_12m_prior": 2,
        "claim_count_36m_prior": 4,
        "claim_count_lifetime_prior": 5,
        "total_incurred_36m_prior": 1.0,
        "avg_incurred_36m_prior": 1.0,
        "max_incurred_36m_prior": 1.0,
        "days_since_last_claim_prior": 10,
        "claim_severity_score_prior": 0.1,
    }

    enriched = enrich_profile_with_claim_history(profile, features)

    assert enriched["claim_count_12m_prior"] == 2
    assert profile["claim_count_12m_prior"] == 999
    assert enriched["line_attributes"] == {"smoker": False}
