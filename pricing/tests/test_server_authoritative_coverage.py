"""Tests for server-authoritative coverage/deductible (spec: hướng 1).

Verifies:
- coverage_amount_vnd / deductible_vnd in quote response come from product, not client
- Client sending different coverage values doesn't change final_premium
- Client-declared asset values (vehicle_value_vnd etc.) are NOT overridden
- Product not found → 4xx RESOURCE_NOT_FOUND
- build_features: PRODUCT_AUTHORITATIVE fields ignore client input
"""
from __future__ import annotations

from unittest.mock import patch, MagicMock

import numpy as np
import pandas as pd
import pytest

from app.pricing_engine import engine
from app.pricing_engine.engine import quote, quote_freq_sev
from app.pricing_engine.features import build_features, PRODUCT_AUTHORITATIVE
from common.errors import ErrorCode, ServiceException


def _valid_profile():
    return {
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


# ── build_features: PRODUCT_AUTHORITATIVE ignores client ──

def test_build_features_coverage_from_product_not_profile():
    """coverage_amount_vnd in feature row comes from product, not profile."""
    prod = {"product_id": "HEALTH_BASIC", "coverage_amount_vnd": 100_000_000,
            "deductible_vnd": 0, "base_premium_vnd": 2_200_000, "admin_fee_vnd": 500_000}
    profile = _valid_profile()
    profile["coverage_amount_vnd"] = 5_000_000_000  # client tries to override

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.get_product", return_value=prod), \
         patch("app.pricing_engine.features.loader.geo_by_province", {}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {}):
        df = build_features("health", "HEALTH_BASIC", profile, ["coverage_amount_vnd"])

    assert int(df.iloc[0]["coverage_amount_vnd"]) == 100_000_000


def test_build_features_deductible_from_product_not_profile():
    """deductible_vnd in feature row comes from product, not profile."""
    prod = {"product_id": "CAR_PHYSICAL_BASIC", "coverage_amount_vnd": 300_000_000,
            "deductible_vnd": 2_000_000, "base_premium_vnd": 5_000_000, "admin_fee_vnd": 60_000}
    profile = _valid_profile()
    profile["line_attributes"]["deductible_vnd"] = 100  # client tries to override

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.get_product", return_value=prod), \
         patch("app.pricing_engine.features.loader.geo_by_province", {}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {}):
        df = build_features("car", "CAR_PHYSICAL_BASIC", profile, ["deductible_vnd"])

    assert int(df.iloc[0]["deductible_vnd"]) == 2_000_000


def test_build_features_vehicle_value_not_overridden():
    """vehicle_value_vnd (client-declared) is NOT in PRODUCT_AUTHORITATIVE."""
    assert "vehicle_value_vnd" not in PRODUCT_AUTHORITATIVE
    assert "declared_property_value_vnd" not in PRODUCT_AUTHORITATIVE
    assert "contents_value_vnd" not in PRODUCT_AUTHORITATIVE
    assert "trip_cost_vnd" not in PRODUCT_AUTHORITATIVE
    assert "death_benefit_vnd" not in PRODUCT_AUTHORITATIVE


def test_build_features_client_vehicle_value_used():
    """Client-supplied vehicle_value_vnd passes through to feature row."""
    prod = {"product_id": "CAR_TPL", "coverage_amount_vnd": 300_000_000,
            "deductible_vnd": 0, "base_premium_vnd": 480_000, "admin_fee_vnd": 80_000}
    profile = _valid_profile()
    profile["line_attributes"]["vehicle_value_vnd"] = 750_000_000

    with patch("app.pricing_engine.features.loader.ensure_loaded"), \
         patch("app.pricing_engine.features.loader.get_product", return_value=prod), \
         patch("app.pricing_engine.features.loader.geo_by_province", {}), \
         patch("app.pricing_engine.features.loader.cost_indices_latest", {}):
        df = build_features("car", "CAR_TPL", profile, ["vehicle_value_vnd"])

    assert int(df.iloc[0]["vehicle_value_vnd"]) == 750_000_000


# ── quote(): coverage from product in response ──

def _mock_quote_setup(coverage=100_000_000, deductible=0, admin_fee=500_000):
    mock_model = MagicMock()
    mock_model.predict.return_value = np.array([500_000.0])
    mock_selection = {
        "model_version": "v1.0",
        "algorithm": "lgb",
        "family": "tw",
        "model": mock_model,
    }
    mock_feature_df = pd.DataFrame([{"age": 30, "coverage_amount_vnd": coverage}])
    prod = {"admin_fee_vnd": admin_fee, "coverage_amount_vnd": coverage,
            "deductible_vnd": deductible, "base_premium_vnd": 2_200_000}
    return mock_selection, mock_feature_df, prod


def test_quote_response_coverage_from_product():
    """Quote response coverage = product value, not client value."""
    mock_selection, mock_feature_df, prod = _mock_quote_setup(coverage=100_000_000)
    profile = _valid_profile()
    profile["coverage_amount_vnd"] = 5_000_000_000  # client sends wrong value

    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.validate_profile"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.engine.select_model", return_value=mock_selection), \
         patch("app.pricing_engine.engine.required_columns", return_value=["age", "coverage_amount_vnd", "deductible_vnd"]), \
         patch("app.pricing_engine.engine.build_features", return_value=mock_feature_df), \
         patch("app.pricing_engine.engine.get_product", return_value=prod), \
         patch("app.pricing_engine.engine.explain", return_value={"available": True, "items": []}), \
         patch("app.pricing_engine.engine.feature_set_for_audit", return_value={}), \
         patch("app.pricing_engine.engine._quote_audit_enabled", return_value=False):
        result = quote(None, "HEALTH_BASIC", profile)

    assert result["coverage_amount_vnd"] == 100_000_000
    assert result["deductible_vnd"] == 0


def test_quote_different_client_coverage_same_premium():
    """Sending different coverage_amount_vnd in profile doesn't change final_premium."""
    mock_selection, mock_feature_df, prod = _mock_quote_setup()
    profile1 = _valid_profile()
    profile1["coverage_amount_vnd"] = 50_000_000
    profile2 = _valid_profile()
    profile2["coverage_amount_vnd"] = 999_999_999

    patches = [
        patch("app.pricing_engine.engine.ensure_loaded"),
        patch("app.pricing_engine.engine.validate_profile"),
        patch("app.pricing_engine.engine.get_line_for_product", return_value="health"),
        patch("app.pricing_engine.engine.LINES", ["health"]),
        patch("app.pricing_engine.engine.select_model", return_value=mock_selection),
        patch("app.pricing_engine.engine.required_columns", return_value=["age", "coverage_amount_vnd", "deductible_vnd"]),
        patch("app.pricing_engine.engine.build_features", return_value=mock_feature_df),
        patch("app.pricing_engine.engine.get_product", return_value=prod),
        patch("app.pricing_engine.engine.explain", return_value={"available": True, "items": []}),
        patch("app.pricing_engine.engine.feature_set_for_audit", return_value={}),
        patch("app.pricing_engine.engine._quote_audit_enabled", return_value=False),
    ]

    for p in patches:
        p.start()
    try:
        r1 = quote(None, "HEALTH_BASIC", profile1)
        r2 = quote(None, "HEALTH_BASIC", profile2)
    finally:
        for p in patches:
            p.stop()

    assert r1["final_premium_vnd"] == r2["final_premium_vnd"]
    assert r1["coverage_amount_vnd"] == 100_000_000
    assert r2["coverage_amount_vnd"] == 100_000_000


def test_quote_response_deductible_from_product():
    """Quote response deductible = product value, not client value."""
    mock_selection, mock_feature_df, prod = _mock_quote_setup(coverage=300_000_000, deductible=2_000_000, admin_fee=60_000)
    profile = _valid_profile()
    profile["line_attributes"]["deductible_vnd"] = 500  # client sends wrong value

    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.validate_profile"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="car"), \
         patch("app.pricing_engine.engine.LINES", ["car"]), \
         patch("app.pricing_engine.engine.select_model", return_value=mock_selection), \
         patch("app.pricing_engine.engine.required_columns", return_value=["age", "coverage_amount_vnd", "deductible_vnd"]), \
         patch("app.pricing_engine.engine.build_features", return_value=mock_feature_df), \
         patch("app.pricing_engine.engine.get_product", return_value=prod), \
         patch("app.pricing_engine.engine.explain", return_value={"available": True, "items": []}), \
         patch("app.pricing_engine.engine.feature_set_for_audit", return_value={}), \
         patch("app.pricing_engine.engine._quote_audit_enabled", return_value=False):
        result = quote(None, "CAR_PHYSICAL_BASIC", profile)

    assert result["deductible_vnd"] == 2_000_000


def test_quote_product_not_found_raises_404():
    """Quote with unknown product_id → RESOURCE_NOT_FOUND, not coverage=0."""
    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.validate_profile"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.engine.get_product", return_value={}):
        with pytest.raises(ServiceException) as exc_info:
            quote(None, "NONEXISTENT_PRODUCT", _valid_profile())
        assert exc_info.value.error_code == ErrorCode.RESOURCE_NOT_FOUND


def test_quote_freq_sev_product_not_found_raises_404():
    """quote_freq_sev with unknown product_id → RESOURCE_NOT_FOUND."""
    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.engine.get_product", return_value={}):
        with pytest.raises(ServiceException) as exc_info:
            quote_freq_sev(None, "NONEXISTENT_PRODUCT", _valid_profile())
        assert exc_info.value.error_code == ErrorCode.RESOURCE_NOT_FOUND


def test_quote_freq_sev_coverage_from_product():
    """quote_freq_sev response coverage = product value."""
    mock_freq = MagicMock()
    mock_freq.predict.return_value = np.array([0.1])
    mock_sev = MagicMock()
    mock_sev.predict.return_value = np.array([5_000_000.0])
    mock_feature_df = pd.DataFrame([{"age": 30}])
    prod = {"admin_fee_vnd": 80_000, "coverage_amount_vnd": 300_000_000,
            "deductible_vnd": 0, "base_premium_vnd": 480_000}
    profile = _valid_profile()
    profile["coverage_amount_vnd"] = 1  # client sends wrong value

    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="car"), \
         patch("app.pricing_engine.engine.LINES", ["car"]), \
         patch("app.pricing_engine.loader.artifacts", {"car": {"freq": mock_freq, "sev": mock_sev}}), \
         patch("app.pricing_engine.engine.required_columns", return_value=["age"]), \
         patch("app.pricing_engine.engine.build_features", return_value=mock_feature_df), \
         patch("app.pricing_engine.engine.get_product", return_value=prod), \
         patch("app.pricing_engine.engine.explain", return_value={"available": False, "items": []}), \
         patch("app.pricing_engine.engine.feature_set_for_audit", return_value={}), \
         patch("app.pricing_engine.engine._quote_audit_enabled", return_value=False):
        result = quote_freq_sev(None, "CAR_TPL", profile)

    assert result["coverage_amount_vnd"] == 300_000_000
    assert result["deductible_vnd"] == 0


# ── PRODUCT_AUTHORITATIVE set contents ──

def test_product_authoritative_contains_expected_fields():
    assert "coverage_amount_vnd" in PRODUCT_AUTHORITATIVE
    assert "deductible_vnd" in PRODUCT_AUTHORITATIVE
    assert "base_premium_vnd" in PRODUCT_AUTHORITATIVE
    assert "admin_fee_vnd" in PRODUCT_AUTHORITATIVE

