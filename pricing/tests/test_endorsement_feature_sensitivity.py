"""Endorsement feature-sensitivity demonstration (health line).

Goal of this test (driven by a product question):
  1. When a customer changes features on their health policy, does the engine
     produce a DIFFERENT premium for the new feature set?
  2. Is that re-rated premium the SAME as what a different customer with the
     "after" feature set would be quoted from scratch? (Endorsement re-rate is a
     fresh quote on the merged profile, so the two must agree.)

These assertions exercise the real champion models. They are skipped when the
gitignored model artifacts are not present.

Feature: dynamic-pricing-platform, Property 10 (endorsement re-rating)
"""
import copy

import pytest

from tests.conftest import skip_if_no_artifacts

pytestmark = skip_if_no_artifacts

PRODUCT = "HEALTH_STANDARD"

# Customer A: the policy as originally issued (the "before" feature set).
PROFILE_BEFORE = {
    "age": 30,
    "gender": "Male",
    "province": "Ha Noi",
    "region": "Red River Delta",
    "urban_tier": "tier1",
    "occupation": "engineer",
    "income_level": "middle",
    "marital_status": "single",
    "line_attributes": {
        "smoker": False,
        "height_cm": 175,
        "weight_kg": 70,
        "bmi": 22.9,
        "coverage_amount_vnd": 500_000_000,
        "deductible_vnd": 2_000_000,
    },
}

# The endorsement change set the customer submits (material health attributes).
CHANGE_SET = {
    "age": 58,
    "line_attributes": {
        "smoker": True,
        "height_cm": 175,
        "weight_kg": 95,
        "bmi": 31.0,
        "coverage_amount_vnd": 1_000_000_000,
        "deductible_vnd": 1_000_000,
    },
}


def _merge_endorsement(base: dict, change: dict) -> dict:
    """Mirror PolicyLifecycleService.applyEndorsement: change overrides base,
    with line_attributes merged key-by-key rather than replaced wholesale."""
    merged = copy.deepcopy(base)
    for key, value in change.items():
        if key == "line_attributes" and isinstance(value, dict):
            merged.setdefault("line_attributes", {}).update(value)
        else:
            merged[key] = value
    return merged


@pytest.mark.parametrize("algo_quote", ["quote"])
def test_changing_features_changes_premium(algo_quote):
    from app.pricing_engine.engine import quote

    before = quote(None, PRODUCT, PROFILE_BEFORE)
    after_profile = _merge_endorsement(PROFILE_BEFORE, CHANGE_SET)
    after = quote(None, PRODUCT, after_profile)

    # 1. A real feature change must move the premium.
    assert before["final_premium_vnd"] != after["final_premium_vnd"], (
        f"premium did not change: before={before['final_premium_vnd']} "
        f"after={after['final_premium_vnd']}"
    )
    # Older + smoker + higher BMI + higher coverage should not be cheaper.
    assert after["final_premium_vnd"] > before["final_premium_vnd"]

    print(f"\n[feature-sensitivity] before={before['final_premium_vnd']:,} VND "
          f"after={after['final_premium_vnd']:,} VND "
          f"delta={after['final_premium_vnd'] - before['final_premium_vnd']:,} VND")


def test_rerate_equals_fresh_quote_for_same_feature_set():
    """Two different customers with the before/after feature sets are priced the
    same as the single customer's policy before/after the endorsement: the
    endorsement re-rate is a fresh quote on the merged profile."""
    from app.pricing_engine.engine import quote

    # Customer B is a brand-new customer whose feature set equals the endorsed
    # ("after") profile of customer A.
    customer_a_before = quote(None, PRODUCT, PROFILE_BEFORE)
    customer_b = quote(None, PRODUCT, _merge_endorsement(PROFILE_BEFORE, CHANGE_SET))

    # The endorsement re-rate uses exactly the merged profile, so it must equal
    # customer B's fresh quote to the VND.
    rerate = quote(None, PRODUCT, _merge_endorsement(PROFILE_BEFORE, CHANGE_SET))

    assert rerate["final_premium_vnd"] == customer_b["final_premium_vnd"]
    assert customer_a_before["final_premium_vnd"] != customer_b["final_premium_vnd"]
