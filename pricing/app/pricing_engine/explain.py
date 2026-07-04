"""SHAP explanation with graceful degradation (Property 7).

Uses TreeExplainer for LightGBM and a linear/fallback path for GLM
pipelines. Returns at least three contributing features with direction
and magnitude. Any failure degrades to explanation.available=false while
the quote itself still succeeds (R5.6).

Requirements: R5.1, R5.2, R5.4, R5.6 (design 3.3).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

_EXPLAINER_CACHE: dict = {}


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
}


def _direction(magnitude: float) -> str:
    return "increase" if magnitude >= 0 else "decrease"


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
        contributions = list(zip(feature_names, arr.tolist()))
        # Sort by absolute magnitude descending; keep available features only.
        excluded = excluded_features or frozenset()
        contributions = [(n, v) for n, v in contributions if n in feature_df.columns and n not in excluded]
        contributions.sort(key=lambda kv: abs(kv[1]), reverse=True)

        items = []
        for name, magnitude in contributions[:10]:
            items.append({
                "feature": name,
                "label": LABEL_EN.get(name, name),
                "direction": _direction(float(magnitude)),
                "magnitude": abs(float(magnitude)),
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
