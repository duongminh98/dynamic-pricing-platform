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
)
from .features import build_features, feature_set_for_audit
from .selection import select_model
from .segment import get_risk_segment
from .explain import explain

QUOTE_VALIDITY_DAYS = 7


# Allowed ranges for core numeric profile fields (R1.2-R1.4, R2.6, R2.17).
PROFILE_RANGES = {
    "age": (18, 100),
    "height_cm": (100, 220),
    "weight_kg": (30, 200),
    "annual_mileage_km": (0, 200_000),
    "vehicle_age": (0, 50),
}


def validate_profile(profile: dict) -> None:
    """Reject numeric profile fields outside their allowed range (Property 6)."""
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
    if family == "freq_sev":
        freq_model = model
        sev_model = model  # placeholder; caller supplies both via selection
        return float(freq_model.predict(feature_df)[0]) * float(sev_model.predict(feature_df)[0])
    # Generic fallback: direct prediction.
    return float(model.predict(feature_df)[0])


def quote(db, product_id: str, profile: dict, model: str | None = None,
          loading_factor: float = 1.0) -> dict:
    """Compute a quote. ``db`` is an optional SQLAlchemy session for audit."""
    ensure_loaded()
    validate_profile(profile)
    line = get_line_for_product(product_id)
    if line not in LINES:
        raise ServiceException(ErrorCode.UNSUPPORTED_LINE, details={"line": line})

    selection = select_model(line, model)
    feature_names = required_columns(line)

    # Validate that required monotonic / core features can be resolved.
    required_core = {"coverage_amount_vnd", "deductible_vnd"}
    missing = [f for f in required_core if f not in feature_names]
    if missing:
        raise ServiceException(ErrorCode.MISSING_FEATURES, details={"missing": missing})

    feature_df = build_features(line, product_id, profile, feature_names)

    pure_premium = _predict_pure_premium(selection, feature_df)

    prod = get_product(product_id)
    admin_fee = prod.get("admin_fee_vnd", 0)
    pure_int, final_int = compute_final_premium(pure_premium, loading_factor, admin_fee)

    created_at = datetime.datetime.now(datetime.timezone.utc)
    expires_at = created_at + datetime.timedelta(days=QUOTE_VALIDITY_DAYS)
    quote_id = str(uuid.uuid4())
    rate_version_id = str(uuid.uuid4())

    explanation = explain(selection["model"], feature_df)
    risk_segment = get_risk_segment(line, profile)

    feature_set = feature_set_for_audit(line, product_id, profile, feature_names)

    if db is not None:
        from ..services.audit import record_audit
        record_audit(db, quote_id, feature_set, selection["model_version"], rate_version_id)

    return {
        "quote_id": quote_id,
        "line": line,
        "product_id": product_id,
        "frequency": None,
        "severity": None,
        "pure_premium_vnd": pure_int,
        "final_premium_vnd": final_int,
        "currency": "VND",
        "expires_at": expires_at.isoformat(),
        "created_at": created_at.isoformat(),
        "explanation": explanation,
        "risk_segment": risk_segment,
        "model_version": selection["model_version"],
        "rate_version": rate_version_id,
        "loading_factor": float(loading_factor),
        "admin_fee_vnd": int(admin_fee),
    }


def quote_freq_sev(db, product_id: str, profile: dict,
                   loading_factor: float = 1.0) -> dict:
    """Quote using the frequency x severity branch (Property 1, freq x sev).

    Uses the champion frequency and severity models of the line.
    """
    ensure_loaded()
    from .loader import artifacts
    line = get_line_for_product(product_id)
    if line not in LINES:
        raise ServiceException(ErrorCode.UNSUPPORTED_LINE, details={"line": line})
    freq_model = artifacts.get(line, {}).get("freq")
    sev_model = artifacts.get(line, {}).get("sev")
    if freq_model is None or sev_model is None:
        raise ServiceException(ErrorCode.MISSING_CHAMPION, details={"line": line})

    feature_names = required_columns(line)
    feature_df = build_features(line, product_id, profile, feature_names)
    frequency = float(freq_model.predict(feature_df)[0])
    severity = float(sev_model.predict(feature_df)[0])
    pure_premium = max(0.0, frequency * severity)

    prod = get_product(product_id)
    admin_fee = prod.get("admin_fee_vnd", 0)
    pure_int, final_int = compute_final_premium(pure_premium, loading_factor, admin_fee)

    created_at = datetime.datetime.now(datetime.timezone.utc)
    expires_at = created_at + datetime.timedelta(days=QUOTE_VALIDITY_DAYS)
    quote_id = str(uuid.uuid4())
    rate_version_id = str(uuid.uuid4())
    explanation = explain(freq_model, feature_df)
    risk_segment = get_risk_segment(line, profile)
    feature_set = feature_set_for_audit(line, product_id, profile, feature_names)
    if db is not None:
        from ..services.audit import record_audit
        record_audit(db, quote_id, feature_set, "freq_sev", rate_version_id)

    return {
        "quote_id": quote_id,
        "line": line,
        "product_id": product_id,
        "frequency": frequency,
        "severity": severity,
        "pure_premium_vnd": pure_int,
        "final_premium_vnd": final_int,
        "currency": "VND",
        "expires_at": expires_at.isoformat(),
        "created_at": created_at.isoformat(),
        "explanation": explanation,
        "risk_segment": risk_segment,
        "model_version": "freq_sev",
        "rate_version": rate_version_id,
        "loading_factor": float(loading_factor),
        "admin_fee_vnd": int(admin_fee),
    }