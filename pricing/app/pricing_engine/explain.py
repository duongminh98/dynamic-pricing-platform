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

# Vietnamese labels for the most important features.
LABEL_VI = {
    "age": "Tuoi",
    "gender": "Gioi tinh",
    "province": "Tinh thanh",
    "region": "Vung",
    "occupation": "Nghe nghiep",
    "income_level": "Muc thu nhap",
    "coverage_amount_vnd": "Muc bao hiem",
    "deductible_vnd": "Muc mien thuong",
    "annual_mileage_km": "So km nam",
    "claim_count_36m_prior": "So lan boi thuong 36 thang",
    "claim_count_12m_prior": "So lan boi thuong 12 thang",
    "vehicle_age": "Tuoi xe",
    "vehicle_value_vnd": "Gia tri xe",
    "smoker": "Thuoc la",
    "bmi": "BMI",
    "trip_duration_days": "Thoi han chuyen di",
    "destination_country": "Quoc gia den",
    "building_age": "Tuoi nha",
    "floor_area_m2": "Dien tich san",
    "occupation_class": "Lop nghe nghiep",
    "sport_risk_level": "Muc do rui ro the thao",
}


def _direction(magnitude: float) -> str:
    return "tang" if magnitude >= 0 else "giam"


def _extract_model_and_features(model):
    """Return (estimator, feature_names) handling both LGBMRegressor and Pipeline."""
    if hasattr(model, "feature_name_"):
        return model, list(model.feature_name_)
    if hasattr(model, "named_steps") and "est" in model.named_steps:
        prep = model.named_steps["prep"]
        cols: list[str] = []
        for _, _, names in prep.transformers:
            cols.extend(names)
        return model.named_steps["est"], cols
    return model, list(getattr(model, "feature_names_in_", []))


def explain(model, feature_df: "pd.DataFrame") -> dict:
    """Produce a SHAP-based explanation; degrade gracefully on any error."""
    try:
        est, feature_names = _extract_model_and_features(model)
        if feature_df is None or feature_df.empty:
            return {"available": False, "items": []}

        import shap

        values = None
        est_name = type(est).__name__.lower()
        is_tree = ("lgbm" in est_name or "xgb" in est_name
                   or "gradient" in est_name or "forest" in est_name
                   or "tree" in est_name)
        if is_tree:
            try:
                explainer = shap.TreeExplainer(est)
                values = explainer.shap_values(feature_df)
            except Exception:
                values = None
        if values is None and hasattr(est, "coef_"):
            try:
                explainer = shap.LinearExplainer(est, feature_df)
                values = explainer.shap_values(feature_df)
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

        arr = np.asarray(values)
        if arr.ndim == 3:
            arr = arr[:, :, 0]
        if arr.ndim == 2:
            arr = arr[0]
        contributions = list(zip(feature_names, arr.tolist()))
        # Sort by absolute magnitude descending; keep available features only.
        contributions = [(n, v) for n, v in contributions if n in feature_df.columns]
        contributions.sort(key=lambda kv: abs(kv[1]), reverse=True)

        items = []
        for name, magnitude in contributions[:10]:
            items.append({
                "feature": name,
                "label_vi": LABEL_VI.get(name, name),
                "direction": _direction(float(magnitude)),
                "magnitude": abs(float(magnitude)),
            })
        if len(items) < 3:
            return {"available": False, "items": []}
        return {"available": True, "items": items[:10]}
    except Exception:
        return {"available": False, "items": []}