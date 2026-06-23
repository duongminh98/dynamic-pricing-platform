"""Shared pytest fixtures + profile generators for Pricing property tests.

Loads model artifacts once per session and provides Hypothesis-style
random-but-valid profile generators aligned to each product line.
"""
from __future__ import annotations

import json
import pathlib
import sys

import pytest

# Make pricing/ importable so `app`, `common` resolve as top-level packages.
PRICING_DIR = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PRICING_DIR))

from app.pricing_engine import loader  # noqa: E402
from app.pricing_engine.engine import quote, quote_freq_sev, compute_final_premium  # noqa: E402

CATS_PATH = PRICING_DIR / "tests" / "line_categories.json"
with open(CATS_PATH, encoding="utf-8") as f:
    LINE_CATEGORIES = json.load(f)

GENDERS = ["Male", "Female"]
OCCUPATIONS = ["engineer", "office_worker", "healthcare_worker", "farmer",
               "business_owner", "driver", "retired", "student"]
INCOME_LEVELS = ["low", "lower_middle", "middle", "upper_middle", "high"]
MARITAL = ["single", "married", "divorced_widowed"]
URBAN_TIERS = ["tier1", "urban", "rural"]
PROVINCES = [
    "Da Nang", "Ha Noi", "Khanh Hoa", "Dong Nai", "Ca Mau", "Quang Ninh",
    "Tay Ninh", "Ha Tinh", "Hue", "Hai Phong", "Bac Ninh", "Can Tho",
]

PRODUCTS_BY_LINE = {
    "health": ["HEALTH_BASIC", "HEALTH_STANDARD", "HEALTH_PREMIUM"],
    "motorbike": ["MOTORBIKE_TPL", "MOTORBIKE_THEFT_FIRE", "MOTORBIKE_COMPREHENSIVE"],
    "car": ["CAR_TPL", "CAR_PHYSICAL_BASIC", "CAR_PHYSICAL_PREMIUM"],
    "home": ["HOME_FIRE_FLOOD_BASIC", "HOME_FIRE_FLOOD_PREMIUM"],
    "accident": ["ACCIDENT_BASIC", "ACCIDENT_STANDARD", "ACCIDENT_PREMIUM"],
    "travel": ["TRAVEL_DOMESTIC", "TRAVEL_INTERNATIONAL"],
}
ALL_LINES = ["health", "motorbike", "car", "home", "accident", "travel"]


@pytest.fixture(scope="session", autouse=True)
def _load_artifacts():
    loader.ensure_loaded()
    yield loader


def line_category_values(line: str, name: str) -> list:
    return LINE_CATEGORIES.get(line, {}).get(name, [])


def make_profile(line: str, **overrides) -> dict:
    """Build a minimal valid profile for a line, with optional overrides."""
    profile = {
        "age": 30,
        "gender": "Male",
        "province": "Ha Noi",
        "region": "Red River Delta",
        "urban_tier": "tier1",
        "occupation": "engineer",
        "income_level": "middle",
        "marital_status": "single",
        "line_attributes": {},
    }
    profile.update(overrides)
    return profile