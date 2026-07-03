"""Deterministic bucket features for pricing models."""
from __future__ import annotations

from typing import Any


AGE_BUCKETS = [
    (18, 35, "18_35"),
    (36, 55, "36_55"),
    (56, 65, "56_65"),
    (66, 75, "66_75"),
]

BMI_BUCKETS = [
    (0.0, 18.5, "underweight"),
    (18.5, 23.0, "normal"),
    (23.0, 25.0, "overweight"),
    (25.0, 30.0, "obese_1"),
    (30.0, 35.0, "obese_2"),
]


def _float_or_none(value: Any) -> float | None:
    if value in (None, ""):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def age_bucket(value: Any) -> str:
    age = _float_or_none(value)
    if age is None:
        return "unknown"
    for low, high, label in AGE_BUCKETS:
        if low <= age <= high:
            return label
    if age > 75:
        return "76_plus"
    return "unknown"


def bmi_bucket(value: Any) -> str:
    bmi = _float_or_none(value)
    if bmi is None:
        return "unknown"
    for low, high, label in BMI_BUCKETS:
        if low <= bmi < high:
            return label
    if bmi >= 35.0:
        return "obese_3_plus"
    return "unknown"


def disease_risk_level(values: dict[str, Any]) -> str:
    flags = [
        "smoker",
        "chronic_disease",
        "diabetes",
        "blood_pressure_problem",
        "hospitalized_last_12m",
        "chronic_condition_flag",
        "diabetes_flag",
        "hypertension_flag",
        "cardiovascular_history",
        "family_medical_history",
    ]
    count = 0
    for flag in flags:
        raw = values.get(flag)
        if isinstance(raw, str):
            active = raw.strip().lower() in {"true", "1", "yes"}
        else:
            active = bool(raw)
        count += int(active)
    count += int(_float_or_none(values.get("pre_existing_conditions_count")) or 0)
    count += int(_float_or_none(values.get("major_surgeries_count")) or 0)
    if count >= 5:
        return "high"
    if count >= 2:
        return "medium"
    return "low"


def add_health_bucket_features(row: dict[str, Any]) -> dict[str, Any]:
    enriched = dict(row)
    age_label = age_bucket(enriched.get("age"))
    bmi_label = bmi_bucket(enriched.get("bmi"))
    disease_label = disease_risk_level(enriched)
    enriched["age_bucket"] = age_label
    enriched["bmi_bucket"] = bmi_label
    enriched["disease_risk_level"] = disease_label
    enriched["age_disease_bucket"] = f"{age_label}__{disease_label}"
    enriched["bmi_disease_bucket"] = f"{bmi_label}__{disease_label}"
    return enriched
