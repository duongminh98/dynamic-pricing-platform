"""Tests for 25.4 pricing fixes: deterministic rate_version + input schema validation.

Feature: dynamic-pricing-platform
Validates: R11.4, R11.5, R32.3
"""
import pytest

from app.pricing_engine.engine import quote, validate_profile
from common.errors import ErrorCode, ServiceException
from tests.conftest import make_profile


def test_rate_version_is_deterministic_for_same_config():
    prof = make_profile("health", line_attributes={"smoker": False, "height_cm": 170, "weight_kg": 65})
    r1 = quote(None, "HEALTH_BASIC", prof)
    r2 = quote(None, "HEALTH_BASIC", prof)
    assert r1["rate_version"] == r2["rate_version"], "rate_version must be stable, not random (R32.3)"
    # Not a throwaway random UUID4 per call.
    assert r1["quote_id"] != r2["quote_id"]


def test_validate_profile_rejects_missing_base_fields():
    with pytest.raises(ServiceException) as exc:
        validate_profile({"age": 30})
    assert exc.value.error_code == ErrorCode.MISSING_FEATURES
    assert "missing" in exc.value.details
