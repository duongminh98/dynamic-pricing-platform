"""AI Pricing Engine quote() implementation (design 3.10, 6.2).

pure_premium = frequency x severity   (freq x sev branch)
            = tweedie prediction       (Tweedie branch, no severity multiply)
final_premium = pure_premium x loading_factor + admin_fee   (>=0, VND int)
expires_at = created_at + 7 days (=168h, BR-5).

Requirements: R4.1-R4.3, R4.5, R4.6, R11.1, R11.5, R11.6, R35.1, R35.5.
"""
from __future__ import annotations

import datetime
import uuid

from common.errors import ErrorCode, ServiceException
from .loader import (
    ensure_loaded, get_line_for_product, get_product, required_columns, LINES,
    get_loading_factor, get_current_rate_version_id,
)
from .features import build_features, feature_set_for_audit
from .selection import select_model
from .explain import explain

QUOTE_VALIDITY_DAYS = 7

def _quote_audit_enabled() -> bool:
    """Read the bonus flag dynamically so tests/deployments can toggle it at runtime."""
    from .. import config
    return config.QUOTE_AUDIT_ENABLED


# Allowed ranges for core numeric profile fields (R1.2-R1.4, R2.6, R2.17).
PROFILE_RANGES = {
    "age": (18, 100),
    "height_cm": (100, 220),
    "weight_kg": (30, 200),
    "annual_mileage_km": (0, 200_000),
    "vehicle_age": (0, 50),
}


RISK_MONOTONE_BASELINES = {
    "health": {
        "smoker": False,
        "chronic_disease": False,
        "diabetes": False,
        "blood_pressure_problem": False,
        "hospitalized_last_12m": False,
        "major_surgeries_count": 0,
        "medical_visit_count_12m": 0,
    },
    "motorbike": {
        "vehicle_age": 0,
        "vehicle_value_vnd": 20_000_000,
        "annual_mileage_km": 0,
        "traffic_violation_count_12m": 0,
        "anti_theft_device": True,
    },
    "car": {
        "vehicle_age": 0,
        "vehicle_value_vnd": 300_000_000,
        "annual_mileage_km": 0,
        "traffic_violation_count_12m": 0,
        "driver_count": 1,
        "anti_theft_device": True,
    },
    "home": {
        "building_age": 0,
        "floor_area_m2": 80,
        "number_of_floors": 1,
        "declared_property_value_vnd": 1_000_000_000,
        "has_fire_alarm": True,
        "has_sprinkler": True,
        "security_system": True,
        "fire_protection": True,
    },
    "accident": {
        "commute_distance_km": 0,
        "sport_activity_flag": False,
    },
    "travel": {
        "trip_duration_days": 1,
        "traveler_count": 1,
        "trip_cost_vnd": 0,
        "has_baggage_cover": False,
        "has_trip_cancellation_cover": False,
    },
}

RISK_ORDERED_BASELINES = {
    "home": {
        "flood_risk_zone": ("low", ["low", "medium", "high"]),
    },
    "accident": {
        "occupation_class": ("low", ["low", "medium", "medium_high", "high"]),
        "workplace_risk_level": ("low", ["low", "medium", "medium_high", "high"]),
        "sport_risk_level": ("none", ["none", "low", "medium", "high"]),
    },
    "travel": {
        "domestic_or_international": ("domestic", ["domestic", "international"]),
    },
}

RISK_NUMERIC_CHECKPOINTS = {
    "health": {
        "major_surgeries_count": [0, 1, 3, 5],
        "medical_visit_count_12m": [0, 2, 8, 12],
    },
}


def _rate_version_for(line: str, model_version: str) -> str:
    """Deterministic Rate_Version id for the rating config in effect (R32.3).
    Derived from line + champion model_version so the same config yields the same
    rate_version across quotes, enabling audit reconciliation."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"rate_version:{line}:{model_version}"))


# Base demographic fields every quote request must carry (input schema, R11.4/R11.5).
REQUIRED_PROFILE_FIELDS = (
    "age", "gender", "province", "region", "urban_tier",
    "occupation", "income_level", "marital_status",
)


def validate_profile(profile: dict) -> None:
    """Validate the input schema then reject out-of-range numeric fields (R11.4/R11.5, Property 6)."""
    if not isinstance(profile, dict):
        raise ServiceException(ErrorCode.MISSING_FEATURES, details={"reason": "profile must be an object"})
    missing = [f for f in REQUIRED_PROFILE_FIELDS if profile.get(f) in (None, "")]
    if missing:
        raise ServiceException(ErrorCode.MISSING_FEATURES, details={"missing": missing})
    merged = dict(profile)
    merged.update(profile.get("line_attributes", {}) or {})
    for field, (lo, hi) in PROFILE_RANGES.items():
        if field in merged:
            try:
                val = float(merged[field])
            except (TypeError, ValueError):
                raise ServiceException(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE,
                                       details={"field": field})
            if not (lo <= val <= hi):
                raise ServiceException(ErrorCode.PROFILE_FIELD_OUT_OF_RANGE,
                                       details={"field": field, "min": lo, "max": hi})

def compute_final_premium(pure_premium: float, loading_factor: float,
                          admin_fee: float) -> tuple[int, int]:
    """Pure formula (Property 1). Both results are >= 0 VND integers."""
    pp = max(0.0, float(pure_premium))
    lf = max(0.0, float(loading_factor))
    af = max(0.0, float(admin_fee))
    pure = int(round(pp))
    final = int(round(pp * lf + af))
    return max(0, pure), max(0, final)


def _predict_pure_premium(selection: dict, feature_df) -> float:
    family = selection["family"]
    model = selection["model"]
    if family == "tw":
        # Tweedie: prediction is already loss per exposure-year (pure premium).
        return float(model.predict(feature_df)[0])
    if family in ("freqsev", "freq_sev"):
        frequency = float(model["freq"].predict(feature_df)[0])
        severity = float(model["sev"].predict(feature_df)[0])
        return max(0.0, frequency * severity)
    # Generic fallback: direct prediction.
    return float(model.predict(feature_df)[0])


def _line_attrs(profile: dict) -> dict:
    return profile.get("line_attributes", {}) or {}

def _attr_value(profile: dict, field: str):
    attrs = _line_attrs(profile)
    if field in attrs:
        return attrs[field]
    return profile.get(field)

def _with_attr(profile: dict, field: str, value) -> dict:
    safer = dict(profile)
    attrs = dict(_line_attrs(profile))
    if field in attrs or field not in safer:
        attrs[field] = value
        safer["line_attributes"] = attrs
    else:
        safer[field] = value
    return safer

def _as_bool(value) -> bool:
    if isinstance(value, str):
        return value.strip().lower() in ("true", "1", "yes")
    return bool(value)

def _is_riskier_than_baseline(value, baseline) -> bool:
    if value in (None, ""):
        return False
    if isinstance(baseline, bool):
        return _as_bool(value) != baseline
    try:
        return float(value) > float(baseline)
    except (TypeError, ValueError):
        return False

def _is_ordered_riskier(value, baseline, ordered_values: list[str]) -> bool:
    if value in (None, "") or value not in ordered_values or baseline not in ordered_values:
        return False
    return ordered_values.index(value) > ordered_values.index(baseline)

def _safer_profiles_for_guard(line: str, profile: dict) -> list[dict]:
    safer_profiles = []
    for field, baseline in RISK_MONOTONE_BASELINES.get(line, {}).items():
        value = _attr_value(profile, field)
        if _is_riskier_than_baseline(value, baseline):
            safer_profiles.append(_with_attr(profile, field, baseline))
    for field, checkpoints in RISK_NUMERIC_CHECKPOINTS.get(line, {}).items():
        value = _attr_value(profile, field)
        try:
            numeric_value = float(value)
        except (TypeError, ValueError):
            continue
        lower_values = [checkpoint for checkpoint in checkpoints if checkpoint < numeric_value]
        if lower_values:
            safer_profiles.append(_with_attr(profile, field, max(lower_values)))
    for field, (baseline, ordered_values) in RISK_ORDERED_BASELINES.get(line, {}).items():
        value = _attr_value(profile, field)
        if _is_ordered_riskier(value, baseline, ordered_values):
            safer_profiles.append(_with_attr(profile, field, baseline))
    return safer_profiles

def _guard_key(line: str, profile: dict) -> tuple:
    fields = (
        set(RISK_MONOTONE_BASELINES.get(line, {}))
        | set(RISK_ORDERED_BASELINES.get(line, {}))
        | set(RISK_NUMERIC_CHECKPOINTS.get(line, {}))
    )
    return tuple((field, repr(_attr_value(profile, field))) for field in sorted(fields))


def _guarded_pure_premium(line: str, product_id: str, profile: dict,
                          selection: dict, feature_names: list[str], feature_df) -> float:
    memo: dict[tuple, float] = {}

    def guarded(current_profile: dict, current_df) -> float:
        key = _guard_key(line, current_profile)
        if key in memo:
            return memo[key]
        guarded_premium = _predict_pure_premium(selection, current_df)
        memo[key] = guarded_premium
        for safer_profile in _safer_profiles_for_guard(line, current_profile):
            safer_df = build_features(line, product_id, safer_profile, feature_names)
            guarded_premium = max(guarded_premium, guarded(safer_profile, safer_df))
        memo[key] = guarded_premium
        return guarded_premium

    return guarded(profile, feature_df)


def quote(db, product_id: str, profile: dict,
          loading_factor: float | None = None) -> dict:
    """Compute a quote. ``db`` is an optional SQLAlchemy session for audit."""
    ensure_loaded()
    validate_profile(profile)
    line = get_line_for_product(product_id)
    if line not in LINES:
        raise ServiceException(ErrorCode.UNSUPPORTED_LINE, details={"line": line})

    prod = get_product(product_id)
    if not prod:
        raise ServiceException(ErrorCode.RESOURCE_NOT_FOUND,
                               details={"product_id": product_id, "reason": "product not found"})
    coverage = int(prod.get("coverage_amount_vnd", 0) or 0)
    deductible = int(prod.get("deductible_vnd", 0) or 0)

    selection = select_model(line)
    feature_names = required_columns(line)

    # Validate that required monotonic / core features can be resolved.
    required_core = {"coverage_amount_vnd", "deductible_vnd"}
    missing = [f for f in required_core if f not in feature_names]
    if missing:
        raise ServiceException(ErrorCode.MISSING_FEATURES, details={"missing": missing})

    feature_df = build_features(line, product_id, profile, feature_names)

    pure_premium = _guarded_pure_premium(line, product_id, profile, selection, feature_names, feature_df)

    if loading_factor is None:
        loading_factor = get_loading_factor(line)

    admin_fee = prod.get("admin_fee_vnd", 0)
    pure_int, final_int = compute_final_premium(pure_premium, loading_factor, admin_fee)

    created_at = datetime.datetime.now(datetime.timezone.utc)
    expires_at = created_at + datetime.timedelta(days=QUOTE_VALIDITY_DAYS)
    quote_id = str(uuid.uuid4())
    # Real Rate_Version: stable id of the rating configuration in effect for this line
    # (champion model_version), not a throwaway random UUID (R32.3).
    rate_version_id = _rate_version_for(line, selection["model_version"])

    explanation = explain(selection["model"], feature_df)
    feature_set = feature_set_for_audit(line, product_id, profile, feature_names)

    if db is not None and _quote_audit_enabled():
        from ..services.audit import record_audit
        record_audit(db, quote_id, feature_set, selection["model_version"], rate_version_id)

    return {
        "quote_id": quote_id,
        "line": line,
        "product_id": product_id,
        "trip_duration_days": profile.get("trip_duration_days") if line == "travel" else None,
        "coverage_amount_vnd": coverage,
        "deductible_vnd": deductible,
        "frequency": None,
        "severity": None,
        "pure_premium_vnd": pure_int,
        "final_premium_vnd": final_int,
        "currency": "VND",
        "expires_at": expires_at.isoformat(),
        "created_at": created_at.isoformat(),
        "explanation": explanation,
        "model_version": selection["model_version"],
        "rate_version": rate_version_id,
        "product_rate_version_id": get_current_rate_version_id(),
        "loading_factor": float(loading_factor),
        "admin_fee_vnd": int(admin_fee),
    }


def quote_freq_sev(db, product_id: str, profile: dict,
                   loading_factor: float | None = None) -> dict:
    """Quote using the frequency x severity branch (Property 1, freq x sev).

    Uses the champion frequency and severity models of the line.
    """
    ensure_loaded()
    from .loader import artifacts
    line = get_line_for_product(product_id)
    if line not in LINES:
        raise ServiceException(ErrorCode.UNSUPPORTED_LINE, details={"line": line})

    prod = get_product(product_id)
    if not prod:
        raise ServiceException(ErrorCode.RESOURCE_NOT_FOUND,
                               details={"product_id": product_id, "reason": "product not found"})
    coverage = int(prod.get("coverage_amount_vnd", 0) or 0)
    deductible = int(prod.get("deductible_vnd", 0) or 0)

    freq_model = artifacts.get(line, {}).get("freq")
    sev_model = artifacts.get(line, {}).get("sev")
    if freq_model is None or sev_model is None:
        raise ServiceException(ErrorCode.MISSING_CHAMPION, details={"line": line})

    feature_names = required_columns(line)
    feature_df = build_features(line, product_id, profile, feature_names)
    frequency = float(freq_model.predict(feature_df)[0])
    severity = float(sev_model.predict(feature_df)[0])
    pure_premium = max(0.0, frequency * severity)

    if loading_factor is None:
        loading_factor = get_loading_factor(line)

    admin_fee = prod.get("admin_fee_vnd", 0)
    pure_int, final_int = compute_final_premium(pure_premium, loading_factor, admin_fee)

    created_at = datetime.datetime.now(datetime.timezone.utc)
    expires_at = created_at + datetime.timedelta(days=QUOTE_VALIDITY_DAYS)
    quote_id = str(uuid.uuid4())
    rate_version_id = str(uuid.uuid4())
    explanation = explain(freq_model, feature_df)
    feature_set = feature_set_for_audit(line, product_id, profile, feature_names)
    if db is not None and _quote_audit_enabled():
        from ..services.audit import record_audit
        record_audit(db, quote_id, feature_set, "freq_sev", rate_version_id)

    return {
        "quote_id": quote_id,
        "line": line,
        "product_id": product_id,
        "coverage_amount_vnd": coverage,
        "deductible_vnd": deductible,
        "frequency": frequency,
        "severity": severity,
        "pure_premium_vnd": pure_int,
        "final_premium_vnd": final_int,
        "currency": "VND",
        "expires_at": expires_at.isoformat(),
        "created_at": created_at.isoformat(),
        "explanation": explanation,
        "model_version": "freq_sev",
        "rate_version": rate_version_id,
        "product_rate_version_id": get_current_rate_version_id(),
        "loading_factor": float(loading_factor),
        "admin_fee_vnd": int(admin_fee),
    }
