"""SHAP explanation with graceful degradation (Property 7).

Uses TreeExplainer for LightGBM and a linear/fallback path for GLM
pipelines. Returns at least three contributing features with direction
and magnitude. Any failure degrades to explanation.available=false while
the quote itself still succeeds (R5.6).

Requirements: R5.1, R5.2, R5.4, R5.6 (design 3.3).
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

_EXPLAINER_CACHE: dict = {}

# Cap the displayed magnitude at +/-300%. A large SHAP margin (e.g. 2.0) maps to
# exp(2)-1 = +639%, which reads as noise to a customer; the cap is presentation
# only and never changes the underlying prediction or ordering.
_PCT_CAP = 3.0

# Internal / engineered features never surfaced to the customer: monotonic
# buckets that duplicate a raw field, pricing internals, height/weight (BMI is
# shown instead), and tenure signals.
_HIDDEN_FEATURES = frozenset({
    "age_bucket", "bmi_bucket", "disease_risk_level",
    "age_disease_bucket", "bmi_disease_bucket",
    "base_premium_vnd", "admin_fee_vnd", "product_id",
    "height_cm", "weight_kg",
    "renewal_number", "years_since_first_policy", "is_renewal", "policy_count_prior",
})

# Two aggregate rows. Members are summed in SHAP (log-margin) space *before*
# converting to a percentage, because exp() is non-linear and per-feature
# percentages are not additive.
_CLAIMS_HISTORY_KEY = "claims_history"
_REGIONAL_RISK_KEY = "regional_risk"

# Allow-list of individual features shown to the customer: base demographics,
# the monetary product terms, and every field the customer actually entered
# (the union of the frontend LINE_FIELDS keys across all six lines). Anything
# not here is either folded into an aggregate row (claims history / regional
# risk) or hidden, so a newly engineered model feature never leaks by default.
_ALLOWED_FEATURES = frozenset({
    # base demographics
    "age", "gender", "province", "region", "urban_tier",
    "occupation", "income_level", "marital_status",
    # monetary product terms
    "coverage_amount_vnd", "deductible_vnd",
    # health
    "bmi", "smoker", "chronic_disease", "diabetes", "blood_pressure_problem",
    "major_surgeries_count", "hospitalized_last_12m", "medical_visit_count_12m",
    # motorbike / car
    "vehicle_brand", "vehicle_model", "vehicle_segment", "vehicle_age",
    "vehicle_value_vnd", "engine_capacity_cc", "driving_experience_years",
    "annual_mileage_km", "traffic_violation_count_12m", "parking_location",
    "anti_theft_device", "primary_use", "driver_count", "garage_repair_option",
    "loan_or_leasing_flag",
    # home
    "property_type", "floor_area_m2", "number_of_floors", "building_age",
    "construction_type", "roof_type", "flood_risk_zone", "fire_protection",
    "has_fire_alarm", "has_sprinkler", "security_system", "declared_property_value_vnd",
    # accident
    "occupation_class", "workplace_risk_level", "commute_mode", "commute_distance_km",
    "sport_activity_flag", "sport_risk_level", "hazardous_activity_exclusion_flag",
    # travel
    "domestic_or_international", "destination_region", "destination_country",
    "trip_duration_days", "traveler_count", "trip_cost_vnd", "travel_purpose",
    "has_baggage_cover", "has_trip_cancellation_cover",
})


def _group_for(name: str) -> str | None:
    """Return the aggregate-group key for a raw feature, or None to show it alone.

    Precedence matters: prior-claim signals (``*_prior``) are claims history even
    when their name also ends in ``_score``; everything else ending in ``_index``
    / ``_score`` (geo risk + cost/inflation indices, derived server-side from
    province and date) collapses into regional risk.
    """
    if name.endswith("_prior"):
        return _CLAIMS_HISTORY_KEY
    if name.endswith("_index") or name.endswith("_score") or name == "distance_to_river_km":
        return _REGIONAL_RISK_KEY
    return None


# Claim-count columns that signal whether the customer has any prior claims.
# When all are zero the "prior claims history" row only reflects the neutral
# point-in-time defaults, so it is hidden to avoid confusing a first-time
# customer with a "prior claims" line they never earned.
_CLAIM_COUNT_FEATURES = (
    "claim_count_lifetime_prior",
    "claim_count_36m_prior",
    "claim_count_12m_prior",
)


def _has_prior_claims(feature_df: "pd.DataFrame") -> bool:
    for col in _CLAIM_COUNT_FEATURES:
        if col in feature_df.columns:
            try:
                if float(feature_df.iloc[0][col]) > 0:
                    return True
            except (TypeError, ValueError):
                continue
    return False


def _get_tree_explainer(est):
    """Cache SHAP TreeExplainer per model to avoid recomputing tree paths."""
    key = id(est)
    if key not in _EXPLAINER_CACHE:
        import shap
        _EXPLAINER_CACHE[key] = shap.TreeExplainer(est)
    return _EXPLAINER_CACHE[key]

LABEL_EN = {
    "age": "Age",
    "gender": "Gender",
    "province": "Province",
    "region": "Region",
    "occupation": "Occupation",
    "income_level": "Income level",
    "coverage_amount_vnd": "Coverage amount",
    "deductible_vnd": "Deductible",
    "annual_mileage_km": "Annual mileage",
    "claim_count_36m_prior": "Claims in last 36 months",
    "claim_count_12m_prior": "Claims in last 12 months",
    "vehicle_age": "Vehicle age",
    "vehicle_value_vnd": "Vehicle value",
    "smoker": "Smoker",
    "bmi": "BMI",
    "trip_duration_days": "Trip duration",
    "destination_country": "Destination country",
    "building_age": "Building age",
    "floor_area_m2": "Floor area",
    "occupation_class": "Occupation class",
    "sport_risk_level": "Sport risk level",
    _CLAIMS_HISTORY_KEY: "Prior claims history",
    _REGIONAL_RISK_KEY: "Regional risk",
}


def _direction(magnitude: float) -> str:
    return "increase" if magnitude >= 0 else "decrease"


def _to_pct(signed: float, method: str, base: float | None) -> float:
    """Convert a signed model-space contribution to a signed premium fraction.

    freq/sev/tweedie all use a log link, so tree/linear SHAP live in log-margin
    space and ``exp(shap) - 1`` is the exact multiplicative effect on that
    component (+0.32 == +32%). The perturbation fallback already produces a
    response-space delta, so it is normalised against the base prediction
    instead of exponentiated. Result is clamped to +/-_PCT_CAP for display.
    """
    if method == "perturbation_fallback":
        pct = signed / base if base else 0.0
    else:
        pct = math.exp(signed) - 1.0
    return max(-_PCT_CAP, min(_PCT_CAP, pct))


def _select_explanation_model(model):
    """Choose the estimator used for explanation and return a method prefix."""
    if isinstance(model, dict):
        if model.get("freq") is not None:
            return model["freq"], "freqsev_frequency"
        if model.get("tw") is not None:
            return model["tw"], "tweedie"
        if model.get("sev") is not None:
            return model["sev"], "freqsev_severity"
    return model, None

def _method_name(prefix: str | None, method: str) -> str:
    return f"{prefix}_{method}" if prefix else method

def _unavailable(method: str = "unavailable") -> dict:
    return {"available": False, "method": method, "items": []}

def _extract_model_and_features(model):
    """Return (estimator, feature_names) handling both LGBMRegressor and Pipeline."""
    model, _ = _select_explanation_model(model)
    if hasattr(model, "feature_name_"):
        return model, list(model.feature_name_)
    if hasattr(model, "named_steps") and "est" in model.named_steps:
        prep = model.named_steps["prep"]
        cols: list[str] = []
        for _, _, names in prep.transformers:
            cols.extend(names)
        return model.named_steps["est"], cols
    return model, list(getattr(model, "feature_names_in_", []))


def _explain_selected_model(model, feature_df: "pd.DataFrame", method_prefix: str | None = None,
                            excluded_features: set[str] | frozenset[str] | None = None) -> dict:
    """Produce an explanation for one concrete estimator."""
    try:
        est, feature_names = _extract_model_and_features(model)
        if feature_df is None or feature_df.empty:
            return _unavailable()

        import shap

        values = None
        method = "unavailable"
        est_name = type(est).__name__.lower()
        is_tree = ("lgbm" in est_name or "xgb" in est_name
                   or "gradient" in est_name or "forest" in est_name
                   or "tree" in est_name)
        if is_tree:
            try:
                explainer = _get_tree_explainer(est)
                values = explainer.shap_values(feature_df)
                method = "tree_shap"
            except Exception:
                values = None
        if values is None and hasattr(est, "coef_"):
            try:
                explainer = shap.LinearExplainer(est, feature_df)
                values = explainer.shap_values(feature_df)
                method = "linear_shap"
            except Exception:
                values = None
        if values is None:
            # Generic fallback: contribution proxy via single-feature
            # perturbation against the base prediction.
            base = float(est.predict(feature_df)[0])
            contribs = []
            for c in feature_names:
                if c not in feature_df.columns:
                    contribs.append(0.0)
                    continue
                perturbed = feature_df.copy()
                perturbed[c] = perturbed[c].mean()
                contribs.append(float(est.predict(perturbed)[0]) - base)
            values = np.array([contribs])
            method = "perturbation_fallback"

        arr = np.asarray(values)
        if arr.ndim == 3:
            arr = arr[:, :, 0]
        if arr.ndim == 2:
            arr = arr[0]

        # Keep the raw *signed* SHAP so groups can be summed in log-margin space
        # before the percentage conversion (per-feature percentages don't add).
        excluded = excluded_features or frozenset()
        singles: list[tuple[str, float]] = []
        group_sums: dict[str, float] = {}
        for name, signed in zip(feature_names, arr.tolist()):
            if name not in feature_df.columns or name in excluded or name in _HIDDEN_FEATURES:
                continue
            signed = float(signed)
            group = _group_for(name)
            if group is not None:
                group_sums[group] = group_sums.get(group, 0.0) + signed
            elif name in _ALLOWED_FEATURES:
                singles.append((name, signed))
            # Not allowed and not grouped -> hidden (never leaked to the customer).

        # A customer with no prior claims only carries the neutral history
        # defaults, so drop the aggregate row rather than show "prior claims".
        if not _has_prior_claims(feature_df):
            group_sums.pop(_CLAIMS_HISTORY_KEY, None)

        base = float(est.predict(feature_df)[0]) if method == "perturbation_fallback" else None
        contributions = singles + list(group_sums.items())
        contributions.sort(key=lambda kv: abs(kv[1]), reverse=True)

        items = []
        for name, signed in contributions[:10]:
            pct = _to_pct(signed, method, base)
            items.append({
                "feature": name,
                "label": LABEL_EN.get(name, name),
                "direction": _direction(signed),
                "magnitude": pct,
            })
        if len(items) < 3:
            return _unavailable(_method_name(method_prefix, method))
        return {"available": True, "method": _method_name(method_prefix, method), "items": items[:10]}
    except Exception:
        return _unavailable()

def _primary_component(components: dict[str, dict]) -> dict:
    for name in ("frequency", "severity", "tweedie"):
        component = components.get(name)
        if component and component.get("available"):
            return component
    return _unavailable()

def explain(model, feature_df: "pd.DataFrame", *,
            component_excluded_features: dict[str, set[str] | frozenset[str]] | None = None,
            excluded_features: set[str] | frozenset[str] | None = None) -> dict:
    """Produce a SHAP-based explanation; degrade gracefully on any error.

    Composite frequency-severity models return component explanations while
    keeping the legacy top-level ``items`` shape for existing clients.
    """
    if isinstance(model, dict):
        components: dict[str, dict] = {}
        component_excluded_features = component_excluded_features or {}
        if model.get("freq") is not None:
            components["frequency"] = _explain_selected_model(
                model["freq"], feature_df, "freqsev_frequency",
                component_excluded_features.get("frequency", excluded_features),
            )
        if model.get("sev") is not None:
            components["severity"] = _explain_selected_model(
                model["sev"], feature_df, "freqsev_severity",
                component_excluded_features.get("severity", excluded_features),
            )
        if model.get("tw") is not None:
            components["tweedie"] = _explain_selected_model(
                model["tw"], feature_df, "tweedie",
                component_excluded_features.get("tweedie", excluded_features),
            )
        primary = _primary_component(components)
        available = any(component.get("available") for component in components.values())
        return {
            "available": available,
            "method": "freqsev_components" if ("frequency" in components or "severity" in components) else primary.get("method", "unavailable"),
            "items": primary.get("items", []),
            "components": components,
        }

    selected_model, method_prefix = _select_explanation_model(model)
    return _explain_selected_model(selected_model, feature_df, method_prefix, excluded_features)
