"""Tests for app.config — feature flags and monotonic exemption logic.

Feature: dynamic-pricing-platform
Validates: R35, BR-19
"""
import os

from app.config import _bool_env, is_monotonic_exempt, MONOTONIC_EXEMPT_LINES


def test_bool_env_true_values():
    for val in ("1", "true", "yes", "on", "TRUE", "Yes", "ON"):
        assert _bool_env("TEST_BOOL", default=False) is False  # env not set
        os.environ["TEST_BOOL"] = val
        assert _bool_env("TEST_BOOL", default=False) is True
        del os.environ["TEST_BOOL"]


def test_bool_env_false_values():
    for val in ("0", "false", "no", "off", "FALSE", "Off"):
        os.environ["TEST_BOOL"] = val
        assert _bool_env("TEST_BOOL", default=True) is False
        del os.environ["TEST_BOOL"]


def test_bool_env_default_when_unset():
    assert _bool_env("NONEXISTENT_ENV_VAR_XYZ", default=True) is True
    assert _bool_env("NONEXISTENT_ENV_VAR_XYZ", default=False) is False


def test_bool_env_empty_string_uses_default():
    os.environ["TEST_BOOL"] = ""
    assert _bool_env("TEST_BOOL", default=True) is True
    assert _bool_env("TEST_BOOL", default=False) is False
    del os.environ["TEST_BOOL"]


def test_monotonic_exempt_travel_glm():
    assert is_monotonic_exempt("travel", "glm") is True
    assert is_monotonic_exempt("travel", "TweedieRegressor") is True
    assert is_monotonic_exempt("travel", "tweedie") is True


def test_monotonic_exempt_travel_non_glm_still_enforced():
    assert is_monotonic_exempt("travel", "lightgbm") is False
    assert is_monotonic_exempt("travel", "xgboost") is False


def test_monotonic_exempt_non_travel_line():
    assert is_monotonic_exempt("health", "glm") is False
    assert is_monotonic_exempt("car", "tweedie") is False


def test_monotonic_exempt_empty_algorithm():
    assert is_monotonic_exempt("travel", "") is False
    assert is_monotonic_exempt("travel", None) is False


def test_monotonic_exempt_lines_contains_travel():
    assert "travel" in MONOTONIC_EXEMPT_LINES
