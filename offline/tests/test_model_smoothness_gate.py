"""Tests for offline model smoothness gate."""
from __future__ import annotations

from offline.model_smoothness_gate import run_sweep


def test_run_sweep_passes_when_adjacent_premiums_are_smooth():
    premiums = {20.0: 100, 25.0: 120, 30.0: 140}

    def fake_quote(_db, _product_id, profile):
        return {"final_premium_vnd": premiums[float(profile["bmi"])]}

    result = run_sweep(
        product_id="HEALTH_BASIC",
        line="health",
        feature="bmi",
        values=[20.0, 25.0, 30.0],
        base_profile={"bmi": 20.0},
        max_adjacent_ratio=2.0,
        quote_func=fake_quote,
    )

    assert result.passed is True
    assert result.failures == []
    assert result.max_adjacent_ratio == 1.2


def test_run_sweep_fails_when_adjacent_premium_cliff_exists():
    premiums = {30.0: 92_000_000, 31.0: 386_000_000}

    def fake_quote(_db, _product_id, profile):
        return {"final_premium_vnd": premiums[float(profile["bmi"])]}

    result = run_sweep(
        product_id="HEALTH_BASIC",
        line="health",
        feature="bmi",
        values=[30.0, 31.0],
        base_profile={"bmi": 30.0},
        max_adjacent_ratio=2.0,
        quote_func=fake_quote,
    )

    assert result.passed is False
    assert len(result.failures) == 1
    assert result.failures[0]["from_value"] == 30.0
    assert result.failures[0]["to_value"] == 31.0
    assert result.failures[0]["adjacent_ratio"] > 4.0
