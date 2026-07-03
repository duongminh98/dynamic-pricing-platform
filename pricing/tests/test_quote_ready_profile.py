from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
import datetime

from app.database import Base, CustomerRiskProfile, QuoteReadyProfile, ClaimOutcome
from app.services.quote_ready_profile import rebuild_quote_ready_profile, rebuild_quote_ready_profiles, get_quote_ready_profile


def _session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    return Session()


def test_rebuild_quote_ready_profile_combines_customer_and_claim_history():
    db = _session()
    db.add(CustomerRiskProfile(
        customer_id="cust-1",
        profile_version=5,
        effective_at=datetime.datetime(2026, 7, 1, tzinfo=datetime.timezone.utc),
        common_risk_attributes={"age": 30, "gender": "Male", "province": "Ha Noi", "region": "Red River Delta", "urban_tier": "tier1", "occupation": "engineer", "income_level": "middle", "marital_status": "single"},
        line_risk_attributes={"health": {"smoker": False, "height_cm": 170, "weight_kg": 65}},
        last_event_id="profile-1",
        updated_at=datetime.datetime(2026, 7, 1, tzinfo=datetime.timezone.utc),
    ))
    db.add(ClaimOutcome(
        claim_id="claim-1",
        customer_id="cust-1",
        quote_id="quote-1",
        policy_id="policy-1",
        exposure_segment_seq=0,
        line="health",
        actual_loss_vnd=2500000,
        claim_status="settled",
        settled_at=datetime.datetime(2026, 6, 1, tzinfo=datetime.timezone.utc),
        recorded_at=datetime.datetime(2026, 6, 1, tzinfo=datetime.timezone.utc),
    ))
    db.commit()

    row = rebuild_quote_ready_profile(db, "cust-1", "health", last_claim_event_id="claim-1")
    db.commit()

    assert row is not None
    projection = db.query(QuoteReadyProfile).filter_by(customer_id="cust-1", line="health").first()
    assert projection is not None
    assert projection.profile_version == 5
    assert projection.enriched_profile["profile_version"] == 5
    assert projection.enriched_profile["claim_count_36m_prior"] == 1
    assert projection.enriched_profile["claim_count_lifetime_prior"] == 1
    assert get_quote_ready_profile(db, "cust-1", "health")["claim_count_36m_prior"] == 1
    db.close()


def test_rebuild_quote_ready_profiles_multiple_lines():
    db = _session()
    db.add(CustomerRiskProfile(
        customer_id="cust-2",
        profile_version=2,
        effective_at=datetime.datetime(2026, 7, 1, tzinfo=datetime.timezone.utc),
        common_risk_attributes={"age": 31, "gender": "Male", "province": "Ha Noi", "region": "Red River Delta", "urban_tier": "tier1", "occupation": "engineer", "income_level": "middle", "marital_status": "single"},
        line_risk_attributes={"car": {"vehicle_plate": "29A-12345", "vehicle_brand": "Toyota", "vehicle_model": "Vios", "vehicle_segment": "standard", "vehicle_age": 4, "vehicle_value_vnd": 300000000, "engine_capacity_cc": 1500, "driving_experience_years": 10, "annual_mileage_km": 12000, "traffic_violation_count_12m": 0, "parking_location": "garage", "anti_theft_device": True, "primary_use": "personal", "garage_repair_option": "authorized", "loan_or_leasing_flag": False}},
        last_event_id="profile-2",
        updated_at=datetime.datetime(2026, 7, 1, tzinfo=datetime.timezone.utc),
    ))
    db.commit()

    rows = rebuild_quote_ready_profiles(db, "cust-2", ["car", "health"])
    db.commit()

    assert len(rows) == 2
    assert db.query(QuoteReadyProfile).filter_by(customer_id="cust-2", line="car").first() is not None
    assert db.query(QuoteReadyProfile).filter_by(customer_id="cust-2", line="health").first() is not None
    db.close()
