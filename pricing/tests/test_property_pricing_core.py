"""Property tests 1, 2, 3, 4, 5 — pricing formula, determinism, expiry,
monotonicity, and risk discrimination.

Feature: dynamic-pricing-platform
Properties: 1, 2, 3, 4, 5  (>=100 Hypothesis iterations each)
Validates: R4.1-R4.3, R4.7-R4.8, R8.3, R11.1, R29.5, R30.5
"""
from __future__ import annotations

import datetime

import pytest
from hypothesis import given, settings, strategies as st

from app.pricing_engine.engine import quote, quote_freq_sev, compute_final_premium
from app.pricing_engine.loader import get_line_for_product, get_product

from tests.conftest import PROVINCES, PRODUCTS_BY_LINE, make_profile

PROPERTY_TAG = "Feature: dynamic-pricing-platform"


# --------------------------------------------------------------------------
# Property 1: pure premium and final premium formula (BR-1)
# --------------------------------------------------------------------------
@given(
    frequency=st.floats(min_value=0.0, max_value=1e6, allow_nan=False, allow_infinity=False),
    severity=st.floats(min_value=0.0, max_value=1e9, allow_nan=False, allow_infinity=False),
    loading_factor=st.floats(min_value=0.0, max_value=10.0, allow_nan=False, allow_infinity=False),
    admin_fee=st.floats(min_value=0.0, max_value=1e7, allow_nan=False, allow_infinity=False),
)
@settings(max_examples=100, deadline=None)
def test_property1_formula_non_negative(frequency, severity, loading_factor, admin_fee):
    """pure_premium = freq x severity; final = pure x loading + admin_fee; both >=0."""
    pure, final = compute_final_premium(frequency * severity, loading_factor, admin_fee)
    assert pure >= 0
    assert final >= 0
    expected_final = int(round(max(0.0, frequency * severity) * max(0.0, loading_factor)
                               + max(0.0, admin_fee)))
    assert final == max(0, expected_final)


@given(
    coverage_amount=st.integers(min_value=10_000_000, max_value=2_000_000_000),
    deductible=st.integers(min_value=0, max_value=5_000_000),
)
@settings(max_examples=100, deadline=None)
def test_property1_freq_sev_branch(coverage_amount, deductible):
    """The freq x sev branch yields pure = freq * severity (Tweedie skipped)."""
    prof = make_profile("car", line_attributes={
        "annual_mileage_km": 12000, "vehicle_age": 4,
        "coverage_amount_vnd": coverage_amount, "deductible_vnd": deductible,
    })
    r = quote_freq_sev(None, "CAR_TPL", prof)
    assert r["frequency"] is not None and r["severity"] is not None
    expected_pure = int(round(max(0.0, r["frequency"] * r["severity"])))
    assert r["pure_premium_vnd"] == max(0, expected_pure)


# --------------------------------------------------------------------------
# Property 2: determinism (idempotence)
# --------------------------------------------------------------------------
@given(
    age=st.integers(min_value=18, max_value=80),
    province=st.sampled_from(PROVINCES),
    coverage=st.integers(min_value=10_000_000, max_value=900_000_000),
)
@settings(max_examples=100, deadline=None)
def test_property2_quote_is_deterministic(age, province, coverage):
    prof = make_profile("car", age=age, province=province,
                        line_attributes={"annual_mileage_km": 12000, "vehicle_age": 4,
                                         "coverage_amount_vnd": coverage})
    r1 = quote(None, "CAR_TPL", prof)
    r2 = quote(None, "CAR_TPL", prof)
    assert r1["pure_premium_vnd"] == r2["pure_premium_vnd"]
    assert r1["final_premium_vnd"] == r2["final_premium_vnd"]


# --------------------------------------------------------------------------
# Property 3: quote validity = 7 days (168h)
# --------------------------------------------------------------------------
@given(
    age=st.integers(min_value=18, max_value=80),
    line=st.sampled_from(["health", "car", "travel"]),
)
@settings(max_examples=100, deadline=None)
def test_property3_expiry_is_7_days(age, line):
    pid = PRODUCTS_BY_LINE[line][0]
    prof = make_profile(line, age=age)
    r = quote(None, pid, prof)
    created = datetime.datetime.fromisoformat(r["created_at"])
    expires = datetime.datetime.fromisoformat(r["expires_at"])
    delta = expires - created
    assert delta == datetime.timedelta(days=7)
    assert r["currency"] == "VND"
    for field in ("quote_id", "pure_premium_vnd", "final_premium_vnd"):
        assert field in r


# --------------------------------------------------------------------------
# Property 4: monotonicity by risk variable (BR-19)
# --------------------------------------------------------------------------
@given(
    coverage=st.integers(min_value=10_000_000, max_value=800_000_000),
)
@settings(max_examples=100, deadline=None)
def test_property4_coverage_monotone_increasing(coverage):
    """coverage_amount_vnd up -> final_premium not lower (car)."""
    base = make_profile("car", line_attributes={"annual_mileage_km": 12000, "vehicle_age": 4})
    low = dict(base); low["line_attributes"] = {**base["line_attributes"], "coverage_amount_vnd": coverage}
    high = dict(base); high["line_attributes"] = {**base["line_attributes"], "coverage_amount_vnd": coverage * 2}
    p_low = quote(None, "CAR_TPL", low)["final_premium_vnd"]
    p_high = quote(None, "CAR_TPL", high)["final_premium_vnd"]
    assert p_high >= p_low


@given(
    deductible=st.integers(min_value=0, max_value=5_000_000),
)
@settings(max_examples=100, deadline=None)
def test_property4_deductible_monotone_decreasing(deductible):
    """deductible_vnd up -> final_premium not higher (health)."""
    base = make_profile("health", line_attributes={"smoker": False, "height_cm": 170, "weight_kg": 65})
    low = dict(base); low["line_attributes"] = {**base["line_attributes"], "deductible_vnd": deductible}
    high = dict(base); high["line_attributes"] = {**base["line_attributes"], "deductible_vnd": deductible + 5_000_000}
    p_low = quote(None, "HEALTH_BASIC", low)["final_premium_vnd"]
    p_high = quote(None, "HEALTH_BASIC", high)["final_premium_vnd"]
    assert p_high <= p_low


@given(
    claims=st.integers(min_value=0, max_value=5),
)
@settings(max_examples=100, deadline=None)
def test_property4_prior_claims_monotone_increasing(claims):
    """claim_count_36m_prior up -> final_premium not lower (health)."""
    base = make_profile("health", line_attributes={"smoker": False, "height_cm": 170, "weight_kg": 65})
    low = dict(base); low["line_attributes"] = {**base["line_attributes"], "claim_count_36m_prior": claims}
    high = dict(base); high["line_attributes"] = {**base["line_attributes"], "claim_count_36m_prior": claims + 3}
    p_low = quote(None, "HEALTH_BASIC", low)["final_premium_vnd"]
    p_high = quote(None, "HEALTH_BASIC", high)["final_premium_vnd"]
    assert p_high >= p_low


@given(
    mileage=st.integers(min_value=1_000, max_value=60_000),
)
@settings(max_examples=100, deadline=None)
def test_property4_mileage_monotone_increasing(mileage):
    """annual_mileage_km up -> final_premium not lower (car)."""
    base = make_profile("car", line_attributes={"vehicle_age": 4})
    low = dict(base); low["line_attributes"] = {**base["line_attributes"], "annual_mileage_km": mileage}
    high = dict(base); high["line_attributes"] = {**base["line_attributes"], "annual_mileage_km": mileage * 2}
    p_low = quote(None, "CAR_TPL", low)["final_premium_vnd"]
    p_high = quote(None, "CAR_TPL", high)["final_premium_vnd"]
    assert p_high >= p_low


# --------------------------------------------------------------------------
# Property 5: price discrimination by risk
# --------------------------------------------------------------------------
@given(
    age_low=st.integers(min_value=18, max_value=40),
    age_high=st.integers(min_value=55, max_value=80),
    claims_high=st.integers(min_value=2, max_value=6),
)
@settings(max_examples=100, deadline=None)
def test_property5_risk_discrimination(age_low, age_high, claims_high):
    """Two clearly different-risk profiles on the same product differ in premium."""
    low_risk = make_profile("health", age=age_low,
                            line_attributes={"smoker": False, "claim_count_36m_prior": 0})
    high_risk = make_profile("health", age=age_high,
                             line_attributes={"smoker": True, "claim_count_36m_prior": claims_high})
    p_low = quote(None, "HEALTH_BASIC", low_risk)["final_premium_vnd"]
    p_high = quote(None, "HEALTH_BASIC", high_risk)["final_premium_vnd"]
    assert abs(p_high - p_low) > 0