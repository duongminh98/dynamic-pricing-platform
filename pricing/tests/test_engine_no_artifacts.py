"""Tests for app.pricing_engine.engine - functions that don't need model artifacts.

Covers validate_profile, compute_final_premium, _rate_version_for,
and quote() with mocked loader/selection.
"""
from __future__ import annotations

import datetime
from unittest.mock import patch, MagicMock

import numpy as np
import pandas as pd
import pytest

from app.pricing_engine import engine
from app.pricing_engine.engine import (
    validate_profile,
    compute_final_premium,
    apply_quote_calibration,
    _rate_version_for,
    _quote_audit_enabled,
    PROFILE_RANGES,
    REQUIRED_PROFILE_FIELDS,
)
from common.errors import ErrorCode, ServiceException


# _rate_version_for

def test_rate_version_for_is_deterministic():
    v1 = _rate_version_for("health", "v1.0")
    v2 = _rate_version_for("health", "v1.0")
    assert v1 == v2


def test_rate_version_for_differs_by_line():
    v1 = _rate_version_for("health", "v1.0")
    v2 = _rate_version_for("car", "v1.0")
    assert v1 != v2


def test_rate_version_for_differs_by_version():
    v1 = _rate_version_for("health", "v1.0")
    v2 = _rate_version_for("health", "v2.0")
    assert v1 != v2


# _quote_audit_enabled

def test_quote_audit_enabled_reads_config():
    with patch("app.config.QUOTE_AUDIT_ENABLED", True):
        assert _quote_audit_enabled() is True
    with patch("app.config.QUOTE_AUDIT_ENABLED", False):
        assert _quote_audit_enabled() is False


# compute_final_premium

def test_compute_final_premium_basic():
    pure, final = compute_final_premium(500_000, 1.2, 10_000)
    assert pure == 500_000
    assert final == 610_000


def test_compute_final_premium_clamps_negative():
    pure, final = compute_final_premium(-100, 1.0, -50)
    assert pure == 0
    assert final == 0


def test_compute_final_premium_zero():
    pure, final = compute_final_premium(0, 1.5, 0)
    assert pure == 0
    assert final == 0


def test_compute_final_premium_rounding():
    pure, final = compute_final_premium(500_000.4, 1.0, 0)
    assert pure == 500_000
    pure2, _ = compute_final_premium(500_000.6, 1.0, 0)
    assert pure2 == 500_001

def test_apply_quote_calibration_preserves_raw_premium_without_runtime_cap():
    prod = {
        "coverage_amount_vnd": 100_000_000,
        "base_premium_vnd": 2_200_000,
        "admin_fee_vnd": 500_000,
    }

    result = apply_quote_calibration("health", prod, 600_000_000, 1.0, 500_000)

    assert result["final_premium_vnd"] == 600_500_000
    assert result["pure_premium_vnd"] == 600_000_000
    assert result["calibration"]["applied"] is False
    assert result["calibration"]["reasons"] == []
    assert result["calibration"]["raw_final_premium_vnd"] == 600_500_000
    assert result["calibration"]["soft_cap_start_final_premium_vnd"] is None

def test_apply_quote_calibration_does_not_floor_to_base_premium():
    prod = {
        "coverage_amount_vnd": 100_000_000,
        "base_premium_vnd": 2_200_000,
        "admin_fee_vnd": 500_000,
    }

    result = apply_quote_calibration("health", prod, 100_000, 1.0, 500_000)

    assert result["final_premium_vnd"] == 600_000
    assert result["pure_premium_vnd"] == 100_000
    assert result["calibration"]["reasons"] == []


# validate_profile

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


def test_validate_profile_accepts_valid():
    validate_profile(_valid_profile())


def test_validate_profile_rejects_non_dict():
    with pytest.raises(ServiceException) as exc_info:
        validate_profile("not a dict")
    assert exc_info.value.error_code == ErrorCode.MISSING_FEATURES


def test_validate_profile_rejects_missing_fields():
    prof = _valid_profile()
    del prof["age"]
    with pytest.raises(ServiceException) as exc_info:
        validate_profile(prof)
    assert exc_info.value.error_code == ErrorCode.MISSING_FEATURES


def test_validate_profile_rejects_empty_fields():
    prof = _valid_profile()
    prof["gender"] = ""
    with pytest.raises(ServiceException) as exc_info:
        validate_profile(prof)
    assert exc_info.value.error_code == ErrorCode.MISSING_FEATURES


def test_validate_profile_rejects_out_of_range_age():
    prof = _valid_profile()
    prof["age"] = 150
    with pytest.raises(ServiceException) as exc_info:
        validate_profile(prof)
    assert exc_info.value.error_code == ErrorCode.PROFILE_FIELD_OUT_OF_RANGE


def test_validate_profile_rejects_out_of_range_height():
    prof = _valid_profile()
    prof["line_attributes"]["height_cm"] = 300
    with pytest.raises(ServiceException) as exc_info:
        validate_profile(prof)
    assert exc_info.value.error_code == ErrorCode.PROFILE_FIELD_OUT_OF_RANGE


def test_validate_profile_rejects_non_numeric_range_field():
    prof = _valid_profile()
    prof["age"] = "abc"
    with pytest.raises(ServiceException) as exc_info:
        validate_profile(prof)
    assert exc_info.value.error_code == ErrorCode.PROFILE_FIELD_OUT_OF_RANGE


def test_validate_profile_accepts_in_range():
    prof = _valid_profile()
    prof["age"] = 18
    prof["line_attributes"]["height_cm"] = 100
    validate_profile(prof)


# quote() with mocked loader

def test_quote_with_mocked_loader():
    mock_model = MagicMock()
    mock_model.predict.return_value = np.array([500_000.0])

    mock_selection = {
        "model_version": "v1.0",
        "algorithm": "lgb",
        "family": "tw",
        "model": mock_model,
    }

    mock_feature_df = pd.DataFrame([{"age": 30, "coverage_amount_vnd": 100_000_000}])

    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.validate_profile"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.engine.select_model", return_value=mock_selection), \
         patch("app.pricing_engine.engine.required_columns", return_value=["age", "coverage_amount_vnd", "deductible_vnd"]), \
         patch("app.pricing_engine.engine.build_features", return_value=mock_feature_df), \
         patch("app.pricing_engine.engine._guarded_pure_premium", return_value=500_000.0), \
         patch("app.pricing_engine.engine.get_product", return_value={"admin_fee_vnd": 10_000, "coverage_amount_vnd": 100_000_000, "deductible_vnd": 0}), \
         patch("app.pricing_engine.engine.explain", return_value={"available": True, "items": []}) as mock_explain, \
         patch("app.pricing_engine.engine.feature_set_for_audit", return_value={"age": 30}), \
         patch("app.pricing_engine.engine.get_loading_factor", return_value=1.0), \
         patch("app.pricing_engine.engine.get_reference_versions", return_value=(None, None)), \
         patch("app.pricing_engine.engine.get_current_rate_version_id", return_value="rv-test"), \
         patch("app.pricing_engine.engine._quote_audit_enabled", return_value=False):
        result = engine.quote(None, "HEALTH_BASIC", _valid_profile())

    assert result["currency"] == "VND"
    assert result["line"] == "health"
    assert result["product_id"] == "HEALTH_BASIC"
    assert result["coverage_amount_vnd"] == 100_000_000
    assert result["deductible_vnd"] == 0
    assert result["pure_premium_vnd"] >= 0
    assert result["final_premium_vnd"] >= 0
    assert result["model_version"] == "v1.0"
    assert "quote_id" in result
    assert "expires_at" in result
    assert "created_at" in result
    assert "rate_version" in result
    assert mock_explain.call_args.kwargs["component_excluded_features"] == {
        "severity": engine.CUSTOMER_EXPLANATION_PRODUCT_FEATURES,
    }


def test_quote_rejects_unsupported_line():
    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.validate_profile"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="unknown"), \
         patch("app.pricing_engine.engine.LINES", ["health"]):
        with pytest.raises(ServiceException) as exc_info:
            engine.quote(None, "UNKNOWN_PRODUCT", _valid_profile())
        assert exc_info.value.error_code == ErrorCode.UNSUPPORTED_LINE


def test_quote_rejects_missing_core_features():
    mock_selection = {
        "model_version": "v1.0",
        "algorithm": "lgb",
        "family": "tw",
        "model": MagicMock(),
    }
    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.validate_profile"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.engine.get_product", return_value={"admin_fee_vnd": 10_000, "coverage_amount_vnd": 100_000_000, "deductible_vnd": 0}), \
         patch("app.pricing_engine.engine.select_model", return_value=mock_selection), \
         patch("app.pricing_engine.engine.required_columns", return_value=["age"]):
        with pytest.raises(ServiceException) as exc_info:
            engine.quote(None, "HEALTH_BASIC", _valid_profile())
        assert exc_info.value.error_code == ErrorCode.MISSING_FEATURES


def test_quote_freq_sev_with_mocked_loader():
    mock_freq = MagicMock()
    mock_freq.predict.return_value = np.array([0.1])
    mock_sev = MagicMock()
    mock_sev.predict.return_value = np.array([5_000_000.0])

    mock_feature_df = pd.DataFrame([{"age": 30}])

    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.loader.artifacts", {"health": {"freq": mock_freq, "sev": mock_sev}}), \
         patch("app.pricing_engine.engine.required_columns", return_value=["age"]), \
         patch("app.pricing_engine.engine.build_features", return_value=mock_feature_df), \
         patch("app.pricing_engine.engine.get_product", return_value={"admin_fee_vnd": 10_000, "coverage_amount_vnd": 100_000_000, "deductible_vnd": 0}), \
         patch("app.pricing_engine.engine.explain", return_value={"available": False, "items": []}) as mock_explain, \
         patch("app.pricing_engine.engine.feature_set_for_audit", return_value={"age": 30}), \
         patch("app.pricing_engine.engine.get_loading_factor", return_value=1.0), \
         patch("app.pricing_engine.engine.get_reference_versions", return_value=(None, None)), \
         patch("app.pricing_engine.engine.get_current_rate_version_id", return_value="rv-test"), \
         patch("app.pricing_engine.engine._quote_audit_enabled", return_value=False):
        result = engine.quote_freq_sev(None, "HEALTH_BASIC", _valid_profile())

    assert result["currency"] == "VND"
    assert result["frequency"] == 0.1
    assert result["severity"] == 5_000_000.0
    assert result["coverage_amount_vnd"] == 100_000_000
    assert mock_explain.call_args.kwargs["component_excluded_features"] == {
        "severity": engine.CUSTOMER_EXPLANATION_PRODUCT_FEATURES,
    }
    assert result["deductible_vnd"] == 0
    assert result["pure_premium_vnd"] >= 0


def test_quote_freq_sev_missing_models():
    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.engine.get_product", return_value={"admin_fee_vnd": 10_000, "coverage_amount_vnd": 100_000_000, "deductible_vnd": 0}), \
         patch("app.pricing_engine.loader.artifacts", {}):
        with pytest.raises(ServiceException) as exc_info:
            engine.quote_freq_sev(None, "HEALTH_BASIC", _valid_profile())
        assert exc_info.value.error_code == ErrorCode.MISSING_CHAMPION


def test_quote_freq_sev_unsupported_line():
    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="unknown"), \
         patch("app.pricing_engine.engine.LINES", ["health"]):
        with pytest.raises(ServiceException) as exc_info:
            engine.quote_freq_sev(None, "UNKNOWN", _valid_profile())
        assert exc_info.value.error_code == ErrorCode.UNSUPPORTED_LINE


def test_quote_rejects_product_not_found():
    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.validate_profile"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.engine.get_product", return_value={}):
        with pytest.raises(ServiceException) as exc_info:
            engine.quote(None, "NONEXISTENT", _valid_profile())
        assert exc_info.value.error_code == ErrorCode.RESOURCE_NOT_FOUND


def test_quote_freq_sev_rejects_product_not_found():
    with patch("app.pricing_engine.engine.ensure_loaded"), \
         patch("app.pricing_engine.engine.get_line_for_product", return_value="health"), \
         patch("app.pricing_engine.engine.LINES", ["health"]), \
         patch("app.pricing_engine.engine.get_product", return_value={}):
        with pytest.raises(ServiceException) as exc_info:
            engine.quote_freq_sev(None, "NONEXISTENT", _valid_profile())
        assert exc_info.value.error_code == ErrorCode.RESOURCE_NOT_FOUND
