"""Smoothness validation gate for offline pricing model promotion.

The gate runs deterministic feature sweeps against the just-trained artifacts.
It is designed to catch local cliffs such as a one-point BMI change producing a
multi-fold premium jump. A failed gate prevents candidate registration, but it
never changes quote-time behavior.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from dataclasses import dataclass
from typing import Any, Iterable

ROOT = pathlib.Path(__file__).resolve().parent.parent
PRICING_DIR = ROOT / "pricing"
if str(PRICING_DIR) not in sys.path:
    sys.path.insert(0, str(PRICING_DIR))


@dataclass(frozen=True)
class SweepPoint:
    feature: str
    value: float
    premium_vnd: int

    def as_dict(self) -> dict[str, Any]:
        return {"feature": self.feature, "value": self.value, "premium_vnd": self.premium_vnd}


@dataclass(frozen=True)
class SmoothnessResult:
    line: str
    product_id: str
    feature: str
    passed: bool
    max_adjacent_ratio: float
    threshold: float
    points: list[SweepPoint]
    failures: list[dict[str, Any]]

    def as_dict(self) -> dict[str, Any]:
        return {
            "line": self.line,
            "product_id": self.product_id,
            "feature": self.feature,
            "passed": self.passed,
            "max_adjacent_ratio": self.max_adjacent_ratio,
            "threshold": self.threshold,
            "points": [p.as_dict() for p in self.points],
            "failures": self.failures,
        }


HEALTH_PRODUCT_ID = "HEALTH_BASIC"
DEFAULT_BMI_VALUES = [20.0, 25.0, 29.0, 30.0, 31.0, 35.0]
DEFAULT_AGE_VALUES = [18.0, 25.0, 35.0, 45.0, 55.0, 60.0, 65.0, 70.0, 75.0]
DEFAULT_MAX_ADJACENT_RATIO = 2.0


def full_disease_health_profile() -> dict[str, Any]:
    """Return a high-risk health profile used for local smoothness sweeps."""
    return {
        "age": 45,
        "gender": "male",
        "province": "Ho Chi Minh",
        "region": "HCM",
        "urban_tier": "urban",
        "occupation": "office_worker",
        "income_level": "middle",
        "marital_status": "single",
        "monthly_income_vnd": 30_000_000,
        "years_with_company": 2,
        "claim_count_12m_prior": 0,
        "claim_count_36m_prior": 0,
        "claim_count_lifetime_prior": 0,
        "total_incurred_36m_prior": 0,
        "avg_incurred_36m_prior": 0,
        "max_incurred_36m_prior": 0,
        "days_since_last_claim_prior": 9999,
        "claim_severity_score_prior": 0,
        "height_cm": 170,
        "weight_kg": 86,
        "bmi": 29.8,
        "smoker": True,
        "chronic_condition_flag": True,
        "diabetes_flag": True,
        "hypertension_flag": True,
        "cardiovascular_history": True,
        "family_medical_history": True,
        "pre_existing_conditions_count": 4,
        "major_surgeries_count": 1,
        "medical_visit_count_12m": 8,
        "coverage_amount_vnd": 500_000_000,
        "deductible_vnd": 0,
        "copay_percent": 0,
    }


def _adjacent_ratio(left: int, right: int) -> float:
    denominator = max(min(left, right), 1)
    return max(left, right) / denominator


def run_sweep(
    *,
    product_id: str,
    line: str,
    feature: str,
    values: Iterable[float],
    base_profile: dict[str, Any],
    max_adjacent_ratio: float = DEFAULT_MAX_ADJACENT_RATIO,
    quote_func=None,
) -> SmoothnessResult:
    """Quote a deterministic sweep and fail if adjacent premiums jump too much."""
    if quote_func is None:
        from app.pricing_engine.engine import quote as quote_func

    points: list[SweepPoint] = []
    for value in values:
        profile = dict(base_profile)
        profile[feature] = value
        response = quote_func(None, product_id, profile)
        points.append(SweepPoint(feature, float(value), int(response["final_premium_vnd"])))

    failures: list[dict[str, Any]] = []
    observed_max = 1.0
    for left, right in zip(points, points[1:]):
        ratio = _adjacent_ratio(left.premium_vnd, right.premium_vnd)
        observed_max = max(observed_max, ratio)
        if ratio > max_adjacent_ratio:
            failures.append({
                "from_value": left.value,
                "to_value": right.value,
                "from_premium_vnd": left.premium_vnd,
                "to_premium_vnd": right.premium_vnd,
                "adjacent_ratio": ratio,
            })

    return SmoothnessResult(
        line=line,
        product_id=product_id,
        feature=feature,
        passed=not failures,
        max_adjacent_ratio=observed_max,
        threshold=max_adjacent_ratio,
        points=points,
        failures=failures,
    )


def run_health_gate(max_adjacent_ratio: float = DEFAULT_MAX_ADJACENT_RATIO) -> list[SmoothnessResult]:
    """Run health-line BMI and age smoothness checks."""
    profile = full_disease_health_profile()
    bmi_result = run_sweep(
        product_id=HEALTH_PRODUCT_ID,
        line="health",
        feature="bmi",
        values=DEFAULT_BMI_VALUES,
        base_profile=profile,
        max_adjacent_ratio=max_adjacent_ratio,
    )
    age_result = run_sweep(
        product_id=HEALTH_PRODUCT_ID,
        line="health",
        feature="age",
        values=DEFAULT_AGE_VALUES,
        base_profile=profile,
        max_adjacent_ratio=max_adjacent_ratio,
    )
    return [bmi_result, age_result]


def run_line_gate(line: str, max_adjacent_ratio: float = DEFAULT_MAX_ADJACENT_RATIO) -> list[SmoothnessResult]:
    """Run smoothness gates relevant to a line."""
    if line != "health":
        return []
    return run_health_gate(max_adjacent_ratio=max_adjacent_ratio)


def main() -> int:
    parser = argparse.ArgumentParser(description="Offline model smoothness gate")
    parser.add_argument("--line", default="health", help="Product line to validate")
    parser.add_argument("--max-adjacent-ratio", type=float, default=DEFAULT_MAX_ADJACENT_RATIO)
    args = parser.parse_args()

    results = run_line_gate(args.line, max_adjacent_ratio=args.max_adjacent_ratio)
    payload = {"line": args.line, "results": [r.as_dict() for r in results]}
    print(json.dumps(payload, indent=2))

    if any(not r.passed for r in results):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
