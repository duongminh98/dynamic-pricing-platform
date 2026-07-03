"""Tests for deterministic bucket feature engineering."""
from app.pricing_engine.feature_buckets import age_bucket, bmi_bucket, add_health_bucket_features


def test_age_bucket_boundaries():
    assert age_bucket(18) == "18_35"
    assert age_bucket(25) == "18_35"
    assert age_bucket(35) == "18_35"
    assert age_bucket(36) == "36_55"
    assert age_bucket(75) == "66_75"
    assert age_bucket(76) == "76_plus"


def test_bmi_bucket_boundaries():
    assert bmi_bucket(20) == "normal"
    assert bmi_bucket(25) == "obese_1"
    assert bmi_bucket(30) == "obese_2"
    assert bmi_bucket(35) == "obese_3_plus"


def test_add_health_bucket_features_includes_interactions():
    row = {
        "age": 55,
        "bmi": 31,
        "smoker": True,
        "diabetes": True,
        "major_surgeries_count": 1,
    }

    enriched = add_health_bucket_features(row)

    assert enriched["age_bucket"] == "36_55"
    assert enriched["bmi_bucket"] == "obese_2"
    assert enriched["disease_risk_level"] == "medium"
    assert enriched["age_disease_bucket"] == "36_55__medium"
    assert enriched["bmi_disease_bucket"] == "obese_2__medium"
