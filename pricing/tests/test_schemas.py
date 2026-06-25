"""Tests for app.schemas — Pydantic model validation.

Feature: dynamic-pricing-platform
"""
import pytest
from pydantic import ValidationError

from app.schemas import Product, Profile


def test_product_valid():
    p = Product(
        product_id="HEALTH_BASIC",
        category="health",
        product_name="Health Basic",
        coverage_amount_vnd=500_000_000,
        deductible_vnd=1_000_000,
        base_premium_vnd=2_000_000,
        admin_fee_vnd=50_000,
        active=True,
    )
    assert p.product_id == "HEALTH_BASIC"
    assert p.active is True


def test_product_missing_field_raises():
    with pytest.raises(ValidationError):
        Product(
            product_id="HEALTH_BASIC",
            category="health",
            product_name="Health Basic",
            coverage_amount_vnd=500_000_000,
            deductible_vnd=1_000_000,
            base_premium_vnd=2_000_000,
            admin_fee_vnd=50_000,
        )


def test_profile_valid():
    p = Profile(
        age=30,
        gender="Male",
        province="Ha Noi",
        region="Red River Delta",
        urban_tier="tier1",
        occupation="engineer",
        income_level="middle",
        monthly_income_vnd=20_000_000,
        marital_status="single",
        line="health",
        line_attributes={"smoker": False, "bmi": 22.5},
    )
    assert p.age == 30
    assert p.line_attributes["smoker"] is False


def test_profile_missing_field_raises():
    with pytest.raises(ValidationError):
        Profile(
            age=30,
            gender="Male",
            province="Ha Noi",
            line="health",
            line_attributes={},
        )


def test_profile_line_attributes_accepts_any_dict():
    p = Profile(
        age=25,
        gender="Female",
        province="Da Nang",
        region="South Central Coast",
        urban_tier="urban",
        occupation="student",
        income_level="low",
        monthly_income_vnd=5_000_000,
        marital_status="single",
        line="travel",
        line_attributes={"destination_country": "Japan", "trip_duration_days": 10},
    )
    assert p.line_attributes["destination_country"] == "Japan"
