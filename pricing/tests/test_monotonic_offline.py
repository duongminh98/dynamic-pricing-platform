"""Offline gate: verify Champion LightGBM artifacts carry monotone_constraints.

This is the monotonicity gate (task 6.3) that MUST pass BEFORE a model is
registered as champion (task 6.2). It checks that the 18 re-fitted LightGBM
artifacts (6 lines x 3 families) carry monotone_constraints aligned to the
feature column order, enforcing BR-19 / C-8:
  coverage_amount_vnd  -> +1  (higher coverage, premium not lower)
  deductible_vnd       -> -1  (higher deductible, premium not higher)
  claim_count_36m_prior-> +1  (more prior claims, premium not lower)
  annual_mileage_km    -> +1  (car/motorbike only)

Feature: dynamic-pricing-platform
Not a property-based test (deterministic artifact inspection).
Validates: R4.7, R12.7, R29.5, R30.5 (BR-19/C-8)
"""
from __future__ import annotations

import pathlib

import joblib
import pytest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODELS_DIR = ROOT / "reports" / "modeling" / "models"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]
FAMILIES = ["freq", "sev", "tw"]

MONOTONE_COMMON = {
    "coverage_amount_vnd": 1,
    "deductible_vnd": -1,
    "claim_count_36m_prior": 1,
}
MONOTONE_VEHICLE = {
    **MONOTONE_COMMON,
    "annual_mileage_km": 1,
}


def _expected_for(line: str) -> dict:
    return MONOTONE_VEHICLE if line in ("car", "motorbike") else MONOTONE_COMMON


@pytest.mark.parametrize("line", LINES)
@pytest.mark.parametrize("family", FAMILIES)
def test_lgb_artifact_has_monotone_constraints(line: str, family: str):
    path = MODELS_DIR / f"{line}__lgb_{family}.joblib"
    if not path.exists():
        pytest.skip(f"artifact {path.name} not present")
    model = joblib.load(path)
    mc = model.get_params().get("monotone_constraints")
    assert mc is not None, f"{path.name} missing monotone_constraints"
    feature_names = list(model.feature_name_)
    assert len(mc) == len(feature_names), (
        f"{path.name}: monotone_constraints length {len(mc)} != "
        f"feature count {len(feature_names)} (misalignment risk)"
    )
    actual = {f: c for f, c in zip(feature_names, mc) if c != 0}
    expected = _expected_for(line)
    for feat, direction in expected.items():
        assert feat in feature_names, f"{path.name}: expected feature {feat} absent"
        assert actual.get(feat) == direction, (
            f"{path.name}: {feat} expected {direction}, got {actual.get(feat)}"
        )


@pytest.mark.parametrize("line", LINES)
def test_monotone_directions_match_design(line: str):
    """The non-zero constraints must equal exactly the design mapping."""
    path = MODELS_DIR / f"{line}__lgb_tw.joblib"
    if not path.exists():
        pytest.skip(f"artifact {path.name} not present")
    model = joblib.load(path)
    mc = model.get_params().get("monotone_constraints")
    feature_names = list(model.feature_name_)
    actual = {f: c for f, c in zip(feature_names, mc) if c != 0}
    expected = _expected_for(line)
    assert actual == expected, (
        f"{line}: non-zero monotone constraints {actual} != expected {expected}"
    )