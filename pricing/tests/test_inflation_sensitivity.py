"""Cost inflation sensitivity checks for pricing quotes.

Feature: dynamic-pricing-platform, Property 17 (feature set uses only approved inputs)
"""
from __future__ import annotations

import copy

import pytest

from tests.conftest import skip_if_no_artifacts

pytestmark = skip_if_no_artifacts

PRODUCT = "HEALTH_STANDARD"
PROFILE = {
    "age": 40,
    "gender": "Male",
    "province": "Ha Noi",
    "region": "Red River Delta",
    "urban_tier": "tier1",
    "occupation": "engineer",
    "income_level": "middle",
    "marital_status": "married",
    "line_attributes": {
        "smoker": False,
        "height_cm": 175,
        "weight_kg": 72,
        "bmi": 23.5,
        "coverage_amount_vnd": 500_000_000,
        "deductible_vnd": 2_000_000,
    },
}


def test_inflation_indices_reach_feature_row(monkeypatch):
    """Server-side inflation indices should be copied into the model feature row."""
    from app.pricing_engine import loader
    from app.pricing_engine.features import build_features
    from app.pricing_engine.loader import required_columns

    feature_names = required_columns("health")
    inflation_features = [name for name in feature_names if name.endswith("_inflation_index")]
    assert inflation_features, "health model should include inflation index features"

    baseline_indices = copy.deepcopy(loader.cost_indices_latest)
    assert baseline_indices, "cost indices should be loaded from cost_indices.csv"

    baseline_features = build_features("health", PRODUCT, PROFILE, feature_names)
    baseline_values = baseline_features.iloc[0][inflation_features].to_dict()
    for name in inflation_features:
        assert baseline_values[name] == pytest.approx(baseline_indices[name])

    stressed_indices = copy.deepcopy(baseline_indices)
    for name in inflation_features:
        stressed_indices[name] = baseline_indices[name] * 1.5
    monkeypatch.setattr(loader, "cost_indices_latest", stressed_indices)

    stressed_features = build_features("health", PRODUCT, PROFILE, feature_names)
    for name in inflation_features:
        assert stressed_features.iloc[0][name] == pytest.approx(stressed_indices[name])


@pytest.mark.xfail(
    strict=True,
    reason="Current champion artifacts include inflation columns, but their predictions are insensitive to them.",
)
def test_inflation_indices_change_quote(monkeypatch):
    """Changing server-side inflation indices should move the quoted premium."""
    from app.pricing_engine import loader
    from app.pricing_engine.engine import quote
    from app.pricing_engine.loader import required_columns

    feature_names = required_columns("health")
    inflation_features = [name for name in feature_names if name.endswith("_inflation_index")]
    assert inflation_features, "health model should include inflation index features"

    baseline_indices = copy.deepcopy(loader.cost_indices_latest)
    baseline_quote = quote(None, PRODUCT, PROFILE)

    stressed_indices = copy.deepcopy(baseline_indices)
    for name in inflation_features:
        stressed_indices[name] = baseline_indices[name] * 1.5
    monkeypatch.setattr(loader, "cost_indices_latest", stressed_indices)

    stressed_quote = quote(None, PRODUCT, PROFILE)

    assert stressed_quote["pure_premium_vnd"] != baseline_quote["pure_premium_vnd"]
    assert stressed_quote["final_premium_vnd"] != baseline_quote["final_premium_vnd"]


def test_product_coverage_amount_changes_quote(monkeypatch):
    """Changing product coverage_amount_vnd should move the quoted premium."""
    from app.pricing_engine import loader
    from app.pricing_engine.engine import quote

    product_id = PRODUCT
    original = copy.deepcopy(loader.get_product(product_id))
    baseline_quote = quote(None, product_id, PROFILE)
    higher_coverage = int(original["coverage_amount_vnd"]) * 2
    monkeypatch.setitem(loader.products, product_id, {**original, "coverage_amount_vnd": higher_coverage})

    stressed_quote = quote(None, product_id, PROFILE)

    assert stressed_quote["coverage_amount_vnd"] == higher_coverage
    assert stressed_quote["pure_premium_vnd"] != baseline_quote["pure_premium_vnd"]
    assert stressed_quote["final_premium_vnd"] != baseline_quote["final_premium_vnd"]


@pytest.mark.xfail(
    strict=True,
    reason="Current health champion artifact includes deductible_vnd, but predictions are insensitive to it.",
)
def test_product_deductible_amount_changes_quote(monkeypatch):
    """Changing product deductible_vnd should move the quoted premium."""
    from app.pricing_engine import loader
    from app.pricing_engine.engine import quote

    product_id = PRODUCT
    original = copy.deepcopy(loader.get_product(product_id))
    baseline_quote = quote(None, product_id, PROFILE)
    higher_deductible = int(original.get("deductible_vnd", 0) or 0) + 5_000_000
    monkeypatch.setitem(loader.products, product_id, {**original, "deductible_vnd": higher_deductible})

    stressed_quote = quote(None, product_id, PROFILE)

    assert stressed_quote["deductible_vnd"] == higher_deductible
    assert stressed_quote["pure_premium_vnd"] != baseline_quote["pure_premium_vnd"]
    assert stressed_quote["final_premium_vnd"] != baseline_quote["final_premium_vnd"]


def test_product_base_premium_amount_changes_quote(monkeypatch):
    """Changing product base_premium_vnd should move the quoted premium."""
    from app.pricing_engine import loader
    from app.pricing_engine.engine import quote

    product_id = PRODUCT
    original = copy.deepcopy(loader.get_product(product_id))
    baseline_quote = quote(None, product_id, PROFILE)
    higher_base_premium = int(original["base_premium_vnd"]) * 2
    monkeypatch.setitem(loader.products, product_id, {**original, "base_premium_vnd": higher_base_premium})

    stressed_quote = quote(None, product_id, PROFILE)

    assert stressed_quote["pure_premium_vnd"] != baseline_quote["pure_premium_vnd"]
    assert stressed_quote["final_premium_vnd"] != baseline_quote["final_premium_vnd"]
