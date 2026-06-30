"""Tests for app.pricing_engine.features — functions that don't need model artifacts.

Covers _cast_value and build_features with mocked loader.
"""
from __future__ import annotations

from unittest.mock import patch

import pandas as pd
import pytest

from app.pricing_engine.features import (
    _cast_value,
    build_features,
    feature_set_for_audit,
    PRIOR_DEFAULTS,
    NUMERIC_DEFAULTS,
    CATEGORICAL_DEFAULTS,
)


def test_cast_value_bool_string_true():
    assert _cast_value("smoker", "true") is True
    assert _cast_value("smoker", "1") is True
    assert _cast_value("smoker", "yes") is True


def test_cast_value_bool_string_false():
    assert _cast_value("smoker", "false") is False
    assert _cast_value("smoker", "0") is False
    assert _cast_value("smoker", "no") is False


def test_cast_value_bool_actual():
    assert _cast_value("smoker", True) is True
    assert _cast_value("smoker", False) is False


def test_cast_value_non_bool_field():
    assert _cast_value("age", 30) == 30
    assert _cast_value("province", "Ha Noi") == "Ha Noi"


def test_cast_value_strips_string():
    assert _cast_value("smoker", "  True  ") is True


def test_prior_defaults_keys():
    assert "claim_count_12m_prior" in PRIOR_DEFAULTS
    assert PRIOR_DEFAULTS["claim_count_12m_prior"] == 0
    assert PRIOR_DEFAULTS["days_since_last_claim_prior"] == 9999


def test_numeric_defaults_keys():
    assert "age" in NUMERIC_DEFAULTS
    assert "vehicle_value_vnd" in NUMERIC_DEFAULTS
    assert NUMERIC_DEFAULTS["age"] == 30


def test_categorical_defaults_keys():
    assert "gender" in CATEGORICAL_DEFAULTS
    assert "province" in CATEGORICAL_DEFAULTS
    assert CATEGORICAL_DEFAULTS["gender"] == "Male"


def test_build_features_with_mocked_loader():
    feature_names = ["age", "gender", "coverage_amount_vnd", "smoker", "claim_count_12m_prior"]
    profile = {
        "age": 35,
        "gender": "Female",
        "province": "Da Nang",
        "line_attributes": {"smoker": True},
    }

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.geo_by_province", {"Da Nang": {"traffic_density_score": 0.5}}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {"medical_inflation_index": 0.03}), \
         patch("app.pricing_engine.features.loader.get_product", return_value={"coverage_amount_vnd": 100_000_000, "admin_fee_vnd": 10_000}):
        df = build_features("health", "HEALTH_BASIC", profile, feature_names)

    assert list(df.columns) == feature_names
    assert df.iloc[0]["age"] == 35
    assert df.iloc[0]["gender"] == "Female"
    assert bool(df.iloc[0]["smoker"]) is True
    assert df.iloc[0]["coverage_amount_vnd"] == 100_000_000
    assert df.iloc[0]["claim_count_12m_prior"] == 0


def test_build_features_fills_defaults():
    feature_names = ["age", "height_cm", "vehicle_value_vnd", "province"]
    profile = {"age": 40, "province": "Unknown", "line_attributes": {}}

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.geo_by_province", {}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {}), \
         patch("app.pricing_engine.features.loader.get_product", return_value={}):
        df = build_features("health", "HEALTH_BASIC", profile, feature_names)

    assert df.iloc[0]["age"] == 40
    assert df.iloc[0]["height_cm"] == 170.0
    assert df.iloc[0]["vehicle_value_vnd"] == 200_000_000
    assert df.iloc[0]["province"] == "Unknown"


def test_build_features_unknown_field_gets_zero():
    feature_names = ["age", "unknown_field"]
    profile = {"age": 30, "line_attributes": {}}

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.geo_by_province", {}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {}), \
         patch("app.pricing_engine.features.loader.get_product", return_value={}):
        df = build_features("health", "HEALTH_BASIC", profile, feature_names)

    assert df.iloc[0]["unknown_field"] == 0


def test_build_features_product_id_override():
    feature_names = ["product_id", "age"]
    profile = {"age": 30, "line_attributes": {}}

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.geo_by_province", {}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {}), \
         patch("app.pricing_engine.features.loader.get_product", return_value={}):
        df = build_features("health", "HEALTH_BASIC", profile, feature_names)

    assert df.iloc[0]["product_id"] == "HEALTH_BASIC"


def test_build_features_casts_object_to_category():
    feature_names = ["gender", "age"]
    profile = {"age": 30, "gender": "Male", "line_attributes": {}}

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.geo_by_province", {}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {}), \
         patch("app.pricing_engine.features.loader.get_product", return_value={}):
        df = build_features("health", "HEALTH_BASIC", profile, feature_names)

    assert str(df["gender"].dtype) == "category"


def test_feature_set_for_audit_with_mocked_loader():
    feature_names = ["age", "gender"]
    profile = {"age": 30, "gender": "Male", "line_attributes": {}}

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.geo_by_province", {}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {}), \
         patch("app.pricing_engine.features.loader.get_product", return_value={}):
        result = feature_set_for_audit("health", "HEALTH_BASIC", profile, feature_names)

    assert result["age"] == 30
    assert result["gender"] == "Male"

