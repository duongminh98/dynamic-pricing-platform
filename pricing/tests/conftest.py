"""Shared pytest fixtures + profile generators for Pricing property tests.

Loads model artifacts once per session and provides Hypothesis-style
random-but-valid profile generators aligned to each product line.

When model artifacts are not present (e.g. CI runner without the gitignored
data/ and reports/ directories) all pricing property tests are skipped.
"""
from __future__ import annotations

import json
import pathlib
import sys

import pytest

# Make pricing/ importable so `app`, `common` resolve as top-level packages.
PRICING_DIR = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PRICING_DIR))

CATS_PATH = PRICING_DIR / "tests" / "line_categories.json"

# Resolve the artifact root the same way loader.py does: four parents up
# from loader.py (i.e. the repo root), NOT relative to pricing/.
_REPO_ROOT = PRICING_DIR.parent
CHAMPION_CONFIG_PATH = _REPO_ROOT / "reports" / "modeling" / "models" / "champion_config.json"
METADATA_PATH = _REPO_ROOT / "data" / "synthetic_real" / "pricing_modeling_metadata.json"

ARTIFACTS_AVAILABLE = CHAMPION_CONFIG_PATH.exists() and METADATA_PATH.exists()

if ARTIFACTS_AVAILABLE:
    from app.pricing_engine import loader  # noqa: E402
    from app.pricing_engine.engine import quote, quote_freq_sev, compute_final_premium  # noqa: E402
else:
    loader = None
    quote = None
    quote_freq_sev = None
    compute_final_premium = None

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

skip_if_no_artifacts = pytest.mark.skipif(
    not ARTIFACTS_AVAILABLE,
    reason="Model artifacts not available (data/ and reports/ are gitignored)",
)


def pytest_collection_modifyitems(config, items):
    """Skip every test in pricing/tests/ when model artifacts are absent."""
    if not ARTIFACTS_AVAILABLE:
        skip = pytest.mark.skip(reason="Model artifacts not available (data/ and reports/ are gitignored)")
        for item in items:
            item.add_marker(skip)


@pytest.fixture(scope="session", autouse=True)
def _load_artifacts():
    if ARTIFACTS_AVAILABLE:
        loader.ensure_loaded()
        yield loader
    else:
        yield None

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
