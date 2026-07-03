import datetime
import os

import pandas as pd
from sqlalchemy import create_engine

from offline.build_training_dataset_from_pricing_db import build_datasets
from app.database import Base, PolicyExposure, ClaimOutcome, QuoteFeatureSnapshot


def test_exporter_builds_frequency_and_severity_dataset(tmp_path):
    db_path = tmp_path / "pricing.sqlite"
    database_url = f"sqlite:///{db_path}"
    engine = create_engine(database_url)
    Base.metadata.create_all(bind=engine)

    from sqlalchemy.orm import sessionmaker
    Session = sessionmaker(bind=engine)
    db = Session()
    start = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    end = datetime.datetime(2027, 1, 1, tzinfo=datetime.timezone.utc)
    db.add(QuoteFeatureSnapshot(
        quote_id="quote-1",
        customer_id="customer-1",
        product_id="CAR_BASIC",
        line="car",
        input_profile={"age": 40},
        enriched_profile={"age": 40},
        feature_set={"age": 40, "vehicle_age": 7, "claim_count_36m_prior": 2},
        model_version_id="model-1",
        rate_version_id="rate-1",
        created_at=start,
    ))
    db.add(PolicyExposure(
        exposure_id="policy-1:0",
        policy_id="policy-1",
        quote_id="quote-1",
        customer_id="customer-1",
        product_id="CAR_BASIC",
        line="car",
        exposure_segment_seq=0,
        segment_start=start,
        segment_end=end,
        earned_exposure_years=1.0,
        coverage_amount_vnd=500000000,
        deductible_vnd=1000000,
        final_premium_vnd=7000000,
        status="active",
        risk_snapshot={"vehicle_age": 5},
        source_event_type="PolicyIssued",
        recorded_at=start,
    ))
    db.add(ClaimOutcome(
        claim_id="claim-1",
        customer_id="customer-1",
        quote_id="quote-1",
        policy_id="policy-1",
        exposure_segment_seq=0,
        line="car",
        loss_type="collision",
        incurred_amount_vnd=3000000,
        paid_amount_vnd=2500000,
        actual_loss_vnd=2500000,
        claim_status="approved",
        occurrence_date=start + datetime.timedelta(days=30),
        reported_at=start + datetime.timedelta(days=31),
        settled_at=start + datetime.timedelta(days=40),
        recorded_at=start + datetime.timedelta(days=40),
    ))
    db.commit()
    db.close()

    written = build_datasets(database_url, tmp_path / "out")
    paths = {path.name for path in written}
    assert "pricing_freq_car.csv" in paths
    assert "pricing_sev_car.csv" in paths

    freq = pd.read_csv(tmp_path / "out" / "pricing_freq_car.csv")
    sev = pd.read_csv(tmp_path / "out" / "pricing_sev_car.csv")
    assert int(freq.loc[0, "claim_count"]) == 1
    assert freq.loc[0, "policy_effective_date"] == "2026-01-01"
    assert int(freq.loc[0, "vehicle_age"]) == 7
    assert int(freq.loc[0, "claim_count_36m_prior"]) == 2
    assert int(sev.loc[0, "incurred_amount"]) == 3000000
    assert int(sev.loc[0, "paid_amount"]) == 2500000
    assert sev.loc[0, "report_date"] == "2026-02-01"
    assert sev.loc[0, "severity_level"] == "low"
    assert int(sev.loc[0, "vehicle_age"]) == 7


def test_exporter_derives_health_bucket_features(tmp_path):
    db_path = tmp_path / "pricing.sqlite"
    database_url = f"sqlite:///{db_path}"
    engine = create_engine(database_url)
    Base.metadata.create_all(bind=engine)

    from sqlalchemy.orm import sessionmaker
    Session = sessionmaker(bind=engine)
    db = Session()
    start = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    end = datetime.datetime(2027, 1, 1, tzinfo=datetime.timezone.utc)
    feature_set = {
        "age": 55,
        "bmi": 31.0,
        "smoker": True,
        "diabetes": True,
        "major_surgeries_count": 1,
    }
    db.add(QuoteFeatureSnapshot(
        quote_id="quote-health-1",
        customer_id="customer-1",
        product_id="HEALTH_BASIC",
        line="health",
        input_profile=feature_set,
        enriched_profile=feature_set,
        feature_set=feature_set,
        model_version_id="model-1",
        rate_version_id="rate-1",
        created_at=start,
    ))
    db.add(PolicyExposure(
        exposure_id="policy-health-1:0",
        policy_id="policy-health-1",
        quote_id="quote-health-1",
        customer_id="customer-1",
        product_id="HEALTH_BASIC",
        line="health",
        exposure_segment_seq=0,
        segment_start=start,
        segment_end=end,
        earned_exposure_years=1.0,
        coverage_amount_vnd=500000000,
        deductible_vnd=0,
        final_premium_vnd=7000000,
        status="active",
        risk_snapshot=feature_set,
        source_event_type="PolicyIssued",
        recorded_at=start,
    ))
    db.commit()
    db.close()

    build_datasets(database_url, tmp_path / "out")

    freq = pd.read_csv(tmp_path / "out" / "pricing_freq_health.csv")
    assert freq.loc[0, "age_bucket"] == "36_55"
    assert freq.loc[0, "bmi_bucket"] == "obese_2"
    assert freq.loc[0, "disease_risk_level"] == "medium"
    assert freq.loc[0, "age_disease_bucket"] == "36_55__medium"
    assert freq.loc[0, "bmi_disease_bucket"] == "obese_2__medium"
