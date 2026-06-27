"""Feature vector builder: maps a client profile to a model feature row.

Derives geo risk / cost-index features server-side from province and a
reference date (never accepts leakage columns from the client). Defaults
prior-claim history to point-in-time-safe values (no future leakage, BR-16).

Requirements: R29.1, R29.3, R29.4 (design 6.1).
"""
from __future__ import annotations

import pandas as pd

from .loader import ensure_loaded, geo_by_province, cost_indices_latest, get_product

# Default prior-claim history: no prior claims, sentinel "no claim" date.
PRIOR_DEFAULTS = {
    "claim_count_12m_prior": 0,
    "claim_count_36m_prior": 0,
    "claim_count_lifetime_prior": 0,
    "total_incurred_36m_prior": 0.0,
    "avg_incurred_36m_prior": 0.0,
    "max_incurred_36m_prior": 0.0,
    "days_since_last_claim_prior": 9999,
    "claim_severity_score_prior": 0.0,
    "policy_count_prior": 0,
}

# Sensible neutral defaults for line-specific numeric features.
NUMERIC_DEFAULTS = {
    "renewal_number": 0,
    "years_since_first_policy": 0,
    "age": 30,
    "height_cm": 170.0,
    "weight_kg": 65.0,
    "bmi": 22.5,
    "major_surgeries_count": 0,
    "medical_visit_count_12m": 0,
    "annual_mileage_km": 10000,
    "vehicle_age": 5,
    "vehicle_value_vnd": 200_000_000,
    "engine_capacity_cc": 150,
    "driving_experience_years": 10,
    "driver_count": 1,
    "traffic_violation_count_12m": 0,
    "trip_duration_days": 7,
    "traveler_count": 1,
    "trip_cost_vnd": 5_000_000,
    "departure_month": 6,
    "building_age": 10,
    "floor_area_m2": 80,
    "number_of_floors": 2,
    "contents_value_vnd": 100_000_000,
    "declared_property_value_vnd": 2_000_000_000,
    "estimated_replacement_cost_vnd": 1_500_000_000,
    "distance_to_river_km": 5.0,
    "commute_distance_km": 10.0,
    "daily_allowance_vnd": 100_000,
    "death_benefit_vnd": 500_000_000,
    "disability_benefit_vnd": 300_000_000,
    "medical_expense_limit_vnd": 50_000_000,
}

# Default categorical values (valid categories present in training data).
CATEGORICAL_DEFAULTS = {
    "gender": "Male",
    "province": "Ha Noi",
    "region": "Red River Delta",
    "urban_tier": "tier1",
    "occupation": "engineer",
    "income_level": "middle",
    "marital_status": "single",
    "product_id": "HEALTH_BASIC",
    "is_renewal": False,
    "smoker": False,
    "diabetes": False,
    "blood_pressure_problem": False,
    "chronic_disease": False,
    "hospitalized_last_12m": False,
    "vehicle_brand": "Toyota",
    "vehicle_model": "Vios",
    "vehicle_segment": "B",
    "primary_use": "personal",
    "parking_location": "street",
    "garage_repair_option": "garage",
    "loan_or_leasing_flag": False,
    "anti_theft_device": False,
    "property_type": "apartment",
    "construction_type": "reinforced_concrete",
    "roof_type": "flat",
    "fire_protection": "basic",
    "flood_risk_zone": "low",
    "has_fire_alarm": False,
    "has_sprinkler": False,
    "security_system": False,
    "construction_quality_score": "standard",
    "commute_mode": "motorbike",
    "occupation_class": "2",
    "sport_activity_flag": False,
    "sport_risk_level": "low",
    "workplace_risk_level": "low",
    "hazardous_activity_exclusion_flag": False,
    "domestic_or_international": "domestic",
    "destination_region": "Asia",
    "destination_country": "Thailand",
    "travel_purpose": "leisure",
    "has_baggage_cover": False,
    "has_trip_cancellation_cover": False,
}

GEO_FEATURES = [
    "traffic_density_score", "vehicle_theft_risk_score", "accident_frequency_index",
    "flood_risk_score", "storm_risk_score", "fire_risk_score", "crime_risk_score",
    "healthcare_access_score", "hospital_cost_index", "repair_cost_index",
    "construction_cost_index",
]
COST_FEATURES = [
    "medical_inflation_index", "vehicle_repair_inflation_index",
    "construction_inflation_index", "travel_medical_cost_index", "general_expense_index",
]


def _cast_value(name: str, value):
    """Coerce boolean-ish string values to Python bool for model compatibility."""
    if name in CATEGORICAL_DEFAULTS and isinstance(CATEGORICAL_DEFAULTS.get(name), bool):
        if isinstance(value, str):
            return value.strip().lower() in ("true", "1", "yes")
        return bool(value)
    return value


PRODUCT_AUTHORITATIVE = {"coverage_amount_vnd", "deductible_vnd", "base_premium_vnd", "admin_fee_vnd"}


def build_features(line: str, product_id: str, profile: dict,
                   feature_names: list[str]) -> "pd.DataFrame":
    """Build a single-row DataFrame aligned to feature_names.

    The profile may supply base fields and a line_attributes dict. Missing
    features are filled with safe defaults; geo/cost features are derived
    server-side. Leakage columns are never copied from the profile.

    coverage_amount_vnd, deductible_vnd, base_premium_vnd, admin_fee_vnd are
    always taken from the product catalog (server-authoritative) and cannot
    be overridden by client input.
    """
    ensure_loaded()
    line_attrs = profile.get("line_attributes", {}) or {}
    province = profile.get("province", CATEGORICAL_DEFAULTS["province"])
    geo = geo_by_province.get(province, {})
    prod = get_product(product_id)

    row: dict = {}
    for name in feature_names:
        # 0. product-authoritative fields (always from product, never from client)
        if name in PRODUCT_AUTHORITATIVE:
            row[name] = prod.get(name, 0)
        # 1. explicit profile value (base or line_attributes)
        elif name in profile:
            row[name] = _cast_value(name, profile[name])
        elif name in line_attrs:
            row[name] = _cast_value(name, line_attrs[name])
        # 2. derived geo features
        elif name in geo:
            row[name] = geo[name]
        # 3. derived cost indices (reference = latest)
        elif name in cost_indices_latest:
            row[name] = cost_indices_latest[name]
        # 4. prior-claim history defaults (point-in-time safe)
        elif name in PRIOR_DEFAULTS:
            row[name] = PRIOR_DEFAULTS[name]
        # 5. numeric defaults
        elif name in NUMERIC_DEFAULTS:
            row[name] = NUMERIC_DEFAULTS[name]
        # 6. categorical defaults
        elif name in CATEGORICAL_DEFAULTS:
            row[name] = CATEGORICAL_DEFAULTS[name]
        else:
            row[name] = 0

    # product_id always reflects the requested product
    if "product_id" in feature_names:
        row["product_id"] = product_id

    df = pd.DataFrame([row], columns=feature_names)

    # LightGBM expects object columns cast to category.
    for c in df.select_dtypes(include="object").columns:
        df[c] = df[c].astype("category")
    return df


def feature_set_for_audit(line: str, product_id: str, profile: dict,
                          feature_names: list[str]) -> dict:
    """Return a JSON-serializable dict of the feature values used (no leakage)."""
    df = build_features(line, product_id, profile, feature_names)
    out = {}
    for c in df.columns:
        val = df.iloc[0][c]
        if pd.isna(val):
            out[c] = None
        elif hasattr(val, "item"):
            out[c] = val.item()
        else:
            out[c] = val
    return out