"""Behavior audit for risk-factor monotonic guardrails.

Feature: dynamic-pricing-platform
Property 4: riskier profile changes must not reduce quoted premium.
"""
from __future__ import annotations

from copy import deepcopy

import pytest

from app.pricing_engine.engine import quote
from tests.conftest import PRODUCTS_BY_LINE, make_profile, skip_if_no_artifacts


BASE_ATTRS = {
    "health": {
        "height_cm": 170,
        "weight_kg": 65,
        "bmi": 22.5,
        "smoker": False,
        "chronic_disease": False,
        "diabetes": False,
        "blood_pressure_problem": False,
        "major_surgeries_count": 0,
        "hospitalized_last_12m": False,
        "medical_visit_count_12m": 0,
    },
    "motorbike": {
        "vehicle_brand": "Honda",
        "vehicle_model": "Wave",
        "vehicle_segment": "standard",
        "vehicle_age": 0,
        "vehicle_value_vnd": 20_000_000,
        "engine_capacity_cc": 110,
        "driving_experience_years": 10,
        "annual_mileage_km": 5_000,
        "traffic_violation_count_12m": 0,
        "parking_location": "garage",
        "anti_theft_device": True,
        "primary_use": "personal",
    },
    "car": {
        "vehicle_brand": "Toyota",
        "vehicle_model": "Vios",
        "vehicle_segment": "standard",
        "vehicle_age": 0,
        "vehicle_value_vnd": 300_000_000,
        "engine_capacity_cc": 1500,
        "driving_experience_years": 10,
        "annual_mileage_km": 5_000,
        "traffic_violation_count_12m": 0,
        "parking_location": "garage",
        "anti_theft_device": True,
        "primary_use": "personal",
        "driver_count": 1,
        "garage_repair_option": "standard",
        "loan_or_leasing_flag": False,
    },
    "home": {
        "property_address": "123 Test Street",
        "property_type": "apartment",
        "floor_area_m2": 80,
        "number_of_floors": 1,
        "building_age": 0,
        "construction_type": "reinforced_concrete",
        "roof_type": "concrete",
        "flood_risk_zone": "low",
        "fire_protection": False,
        "has_fire_alarm": True,
        "has_sprinkler": True,
        "security_system": True,
        "declared_property_value_vnd": 1_000_000_000,
    },
    "accident": {
        "occupation_class": "low",
        "workplace_risk_level": "low",
        "commute_mode": "public_transport",
        "commute_distance_km": 0,
        "sport_activity_flag": False,
        "sport_risk_level": "none",
        "hazardous_activity_exclusion_flag": False,
    },
    "travel": {
        "trip_start_date": "2026-07-01",
        "trip_end_date": "2026-07-02",
        "domestic_or_international": "domestic",
        "destination_region": "Vietnam",
        "destination_country": "Vietnam",
        "trip_duration_days": 1,
        "traveler_count": 1,
        "trip_cost_vnd": 1_000_000,
        "travel_purpose": "leisure",
        "has_baggage_cover": False,
        "has_trip_cancellation_cover": False,
    },
}

RISK_STEPS = [
    ("health", "smoker", True),
    ("health", "chronic_disease", True),
    ("health", "diabetes", True),
    ("health", "blood_pressure_problem", True),
    ("health", "major_surgeries_count", 3),
    ("health", "hospitalized_last_12m", True),
    ("health", "medical_visit_count_12m", 8),
    ("motorbike", "vehicle_age", 10),
    ("motorbike", "vehicle_value_vnd", 80_000_000),
    ("motorbike", "annual_mileage_km", 30_000),
    ("motorbike", "traffic_violation_count_12m", 4),
    ("motorbike", "anti_theft_device", False),
    ("car", "vehicle_age", 10),
    ("car", "vehicle_value_vnd", 900_000_000),
    ("car", "annual_mileage_km", 30_000),
    ("car", "traffic_violation_count_12m", 4),
    ("car", "driver_count", 3),
    ("car", "anti_theft_device", False),
    ("home", "building_age", 30),
    ("home", "floor_area_m2", 200),
    ("home", "number_of_floors", 5),
    ("home", "declared_property_value_vnd", 5_000_000_000),
    ("home", "flood_risk_zone", "high"),
    ("home", "has_fire_alarm", False),
    ("home", "has_sprinkler", False),
    ("home", "security_system", False),
    ("accident", "occupation_class", "high"),
    ("accident", "workplace_risk_level", "high"),
    ("accident", "commute_distance_km", 80),
    ("accident", "sport_activity_flag", True),
    ("accident", "sport_risk_level", "high"),
    ("travel", "trip_duration_days", 30),
    ("travel", "traveler_count", 4),
    ("travel", "trip_cost_vnd", 100_000_000),
    ("travel", "domestic_or_international", "international"),
    ("travel", "has_baggage_cover", True),
    ("travel", "has_trip_cancellation_cover", True),
]

CLAIM_HISTORY_SCENARIOS = [
    {
        "claim_count_12m_prior": 1,
        "claim_count_36m_prior": 1,
        "claim_count_lifetime_prior": 1,
        "total_incurred_36m_prior": 12_000_000,
        "avg_incurred_36m_prior": 12_000_000,
        "max_incurred_36m_prior": 12_000_000,
        "days_since_last_claim_prior": 120,
        "claim_severity_score_prior": 0.35,
        "policy_count_prior": 1,
    },
    {
        "claim_count_12m_prior": 2,
        "claim_count_36m_prior": 3,
        "claim_count_lifetime_prior": 4,
        "total_incurred_36m_prior": 90_000_000,
        "avg_incurred_36m_prior": 30_000_000,
        "max_incurred_36m_prior": 50_000_000,
        "days_since_last_claim_prior": 45,
        "claim_severity_score_prior": 0.85,
        "policy_count_prior": 2,
    },
]

RENEWAL_GOOD_HISTORY = {
    "renewal_number": 3,
    "is_renewal": True,
    "years_since_first_policy": 3.0,
    "policy_count_prior": 3,
    "days_since_last_claim_prior": 9999,
}

RENEWAL_WITH_CLAIMS = {
    **RENEWAL_GOOD_HISTORY,
    "claim_count_12m_prior": 1,
    "claim_count_36m_prior": 2,
    "claim_count_lifetime_prior": 3,
    "total_incurred_36m_prior": 60_000_000,
    "avg_incurred_36m_prior": 30_000_000,
    "max_incurred_36m_prior": 40_000_000,
    "days_since_last_claim_prior": 90,
    "claim_severity_score_prior": 0.7,
}

CLAIM_HISTORY_LADDERS = [
    ("claim_count_12m_prior", [0, 1, 2]),
    ("claim_count_36m_prior", [0, 1, 3]),
    ("claim_count_lifetime_prior", [0, 1, 4]),
    ("total_incurred_36m_prior", [0, 12_000_000, 90_000_000]),
    ("avg_incurred_36m_prior", [0, 12_000_000, 30_000_000]),
    ("max_incurred_36m_prior", [0, 12_000_000, 50_000_000]),
    ("claim_severity_score_prior", [0.0, 0.35, 0.85]),
]

CLAIM_RECENCY_VALUES = [9999, 365, 45]

POLICY_TENURE_LADDERS = [
    ("renewal_number", [0, 1, 3, 5]),
    ("years_since_first_policy", [0.0, 1.0, 3.0, 5.0]),
    ("policy_count_prior", [0, 1, 3, 5]),
    ("is_renewal", [False, True]),
]


def profile_for(line: str, attrs: dict | None = None) -> dict:
    profile = make_profile(line, line_attributes=deepcopy(BASE_ATTRS[line]))
    if attrs:
        profile["line_attributes"].update(attrs)
    return profile

def final_premium(product_id: str, line: str, attrs: dict | None = None) -> int:
    return quote(None, product_id, profile_for(line, attrs))["final_premium_vnd"]


@skip_if_no_artifacts
@pytest.mark.parametrize(("line", "field", "riskier_value"), RISK_STEPS)
def test_riskier_single_feature_does_not_reduce_premium(line, field, riskier_value):
    product_id = PRODUCTS_BY_LINE[line][0]
    safer = profile_for(line)
    riskier = profile_for(line, {field: riskier_value})

    safer_premium = quote(None, product_id, safer)["final_premium_vnd"]
    riskier_premium = quote(None, product_id, riskier)["final_premium_vnd"]

    assert riskier_premium >= safer_premium, (
        f"{line}.{field} reduced premium: safer={safer_premium}, "
        f"riskier={riskier_premium}, riskier_value={riskier_value!r}"
    )


@skip_if_no_artifacts
def test_health_smoker_full_disease_combo_not_cheaper_than_component_profiles():
    product_id = "HEALTH_STANDARD"
    smoker_only = profile_for("health", {"smoker": True})
    full_disease = profile_for("health", {
        "chronic_disease": True,
        "diabetes": True,
        "blood_pressure_problem": True,
        "major_surgeries_count": 5,
        "hospitalized_last_12m": True,
        "medical_visit_count_12m": 12,
    })
    full_disease_smoker = profile_for("health", {
        "smoker": True,
        "chronic_disease": True,
        "diabetes": True,
        "blood_pressure_problem": True,
        "major_surgeries_count": 5,
        "hospitalized_last_12m": True,
        "medical_visit_count_12m": 12,
    })

    smoker_premium = quote(None, product_id, smoker_only)["final_premium_vnd"]
    disease_premium = quote(None, product_id, full_disease)["final_premium_vnd"]
    combo_premium = quote(None, product_id, full_disease_smoker)["final_premium_vnd"]

    assert combo_premium >= smoker_premium
    assert combo_premium > disease_premium
@skip_if_no_artifacts
def test_health_single_factor_surcharges_are_calibrated():
    product_id = "HEALTH_STANDARD"
    baseline = final_premium(product_id, "health")
    smoker = final_premium(product_id, "health", {"smoker": True})
    chronic = final_premium(product_id, "health", {"chronic_disease": True})
    full_disease = final_premium(product_id, "health", {
        "chronic_disease": True,
        "diabetes": True,
        "blood_pressure_problem": True,
        "major_surgeries_count": 5,
        "hospitalized_last_12m": True,
        "medical_visit_count_12m": 12,
    })

    assert smoker <= baseline * 4
    assert chronic <= baseline * 4
    assert full_disease >= smoker
    assert full_disease >= chronic

@skip_if_no_artifacts
@pytest.mark.parametrize("product_id", PRODUCTS_BY_LINE["health"])
@pytest.mark.parametrize(("field", "values"), [
    ("major_surgeries_count", [0, 1, 3, 5]),
    ("medical_visit_count_12m", [0, 2, 8, 12]),
    ("hospitalized_last_12m", [False, True]),
])
def test_health_utilization_ladders_do_not_reduce_premium(product_id, field, values):
    premiums = [final_premium(product_id, "health", {field: value}) for value in values]

    assert premiums == sorted(premiums), (
        f"health.{field} should be non-decreasing for {product_id}: "
        f"values={values}, premiums={premiums}"
    )

@skip_if_no_artifacts
@pytest.mark.parametrize("product_id", PRODUCTS_BY_LINE["health"])
def test_health_medical_history_combo_is_not_cheaper_than_individual_utilization(product_id):
    surgery = final_premium(product_id, "health", {"major_surgeries_count": 3})
    hospitalized = final_premium(product_id, "health", {"hospitalized_last_12m": True})
    frequent_visits = final_premium(product_id, "health", {"medical_visit_count_12m": 12})
    combined_history = final_premium(product_id, "health", {
        "major_surgeries_count": 3,
        "hospitalized_last_12m": True,
        "medical_visit_count_12m": 12,
    })

    assert combined_history >= surgery
    assert combined_history >= hospitalized
    assert combined_history >= frequent_visits

@skip_if_no_artifacts
@pytest.mark.parametrize("line", sorted(PRODUCTS_BY_LINE))
@pytest.mark.parametrize("claim_history", CLAIM_HISTORY_SCENARIOS)
def test_prior_claim_history_does_not_reduce_premium(line, claim_history):
    product_id = PRODUCTS_BY_LINE[line][0]
    baseline = final_premium(product_id, line)
    with_claim_history = final_premium(product_id, line, claim_history)

    assert with_claim_history >= baseline, (
        f"{line} prior claim history reduced premium: baseline={baseline}, "
        f"with_claim_history={with_claim_history}, claim_history={claim_history!r}"
    )

@skip_if_no_artifacts
@pytest.mark.parametrize("line", sorted(PRODUCTS_BY_LINE))
def test_renewal_with_claims_is_not_cheaper_than_clean_renewal(line):
    product_id = PRODUCTS_BY_LINE[line][0]
    clean_renewal = final_premium(product_id, line, RENEWAL_GOOD_HISTORY)
    renewal_with_claims = final_premium(product_id, line, RENEWAL_WITH_CLAIMS)

    assert renewal_with_claims >= clean_renewal, (
        f"{line} renewal claim history reduced premium: clean_renewal={clean_renewal}, "
        f"renewal_with_claims={renewal_with_claims}"
    )

@skip_if_no_artifacts
@pytest.mark.parametrize("line", sorted(PRODUCTS_BY_LINE))
@pytest.mark.parametrize(("field", "values"), CLAIM_HISTORY_LADDERS)
def test_claim_history_ladders_do_not_reduce_premium(line, field, values):
    product_id = PRODUCTS_BY_LINE[line][0]
    premiums = [final_premium(product_id, line, {field: value}) for value in values]

    assert premiums == sorted(premiums), (
        f"{line}.{field} should be non-decreasing: values={values}, premiums={premiums}"
    )

@skip_if_no_artifacts
@pytest.mark.parametrize("line", sorted(PRODUCTS_BY_LINE))
def test_more_recent_claim_does_not_reduce_premium(line):
    product_id = PRODUCTS_BY_LINE[line][0]
    premiums = [
        final_premium(product_id, line, {
            "claim_count_12m_prior": 1,
            "claim_count_36m_prior": 1,
            "claim_count_lifetime_prior": 1,
            "total_incurred_36m_prior": 12_000_000,
            "avg_incurred_36m_prior": 12_000_000,
            "max_incurred_36m_prior": 12_000_000,
            "claim_severity_score_prior": 0.35,
            "days_since_last_claim_prior": days,
        })
        for days in CLAIM_RECENCY_VALUES
    ]

    assert premiums == sorted(premiums), (
        f"{line}.days_since_last_claim_prior should be non-increasing by days: "
        f"days={CLAIM_RECENCY_VALUES}, premiums={premiums}"
    )

@skip_if_no_artifacts
@pytest.mark.parametrize("line", sorted(PRODUCTS_BY_LINE))
@pytest.mark.parametrize(("field", "values"), POLICY_TENURE_LADDERS)
def test_policy_tenure_features_have_bounded_effect(line, field, values):
    product_id = PRODUCTS_BY_LINE[line][0]
    premiums = [final_premium(product_id, line, {field: value}) for value in values]
    lowest = min(premiums)
    highest = max(premiums)

    assert lowest > 0
    assert highest <= lowest * 1.5, (
        f"{line}.{field} tenure effect is too volatile: values={values}, premiums={premiums}"
    )
