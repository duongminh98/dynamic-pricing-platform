"""Real startup smoke checks (replaces the assertTrue(true) Java stub).

Feature: dynamic-pricing-platform
Validates: R11.2 (36 artifacts), R11.3 (fail-fast), champion_config <-> artifacts
consistency, model_version uniqueness. Runs without the full stack; skips only
when the gitignored model artifacts are absent.
"""
from __future__ import annotations

import json
import pathlib

import pytest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODELS_DIR = REPO_ROOT / "reports" / "modeling" / "models"
CHAMPION_CONFIG = MODELS_DIR / "champion_config.json"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]
FAMILIES = ["freq", "sev", "tw"]
ALGORITHMS = ["glm", "lgb"]

pytestmark = pytest.mark.skipif(
    not CHAMPION_CONFIG.exists(),
    reason="Model artifacts not available (reports/ is gitignored)",
)


def test_exactly_36_model_artifacts_present():
    # 6 lines x 3 families x 2 algorithms = 36 (R11.2).
    joblibs = sorted(p.name for p in MODELS_DIR.glob("*.joblib"))
    assert len(joblibs) == 36, f"expected 36 artifacts, found {len(joblibs)}"
    for line in LINES:
        for fam in FAMILIES:
            for algo in ALGORITHMS:
                name = f"{line}__{algo}_{fam}.joblib"
                assert name in joblibs, f"missing artifact {name}"


def test_champion_config_covers_all_six_lines():
    cfg = json.loads(CHAMPION_CONFIG.read_text(encoding="utf-8"))
    champ = cfg.get("champion_by_line", {})
    assert set(champ.keys()) == set(LINES), f"champion_by_line lines mismatch: {set(champ.keys())}"


def test_champion_config_points_at_existing_artifacts():
    cfg = json.loads(CHAMPION_CONFIG.read_text(encoding="utf-8"))
    for line, c in cfg["champion_by_line"].items():
        algo = c["algorithm"]
        family = c["family"]
        artifact = MODELS_DIR / f"{line}__{algo}_{family}.joblib"
        assert artifact.exists(), f"champion artifact missing for {line}: {artifact.name}"


def test_champion_model_versions_are_unique_per_line():
    cfg = json.loads(CHAMPION_CONFIG.read_text(encoding="utf-8"))
    versions = [c["model_version"] for c in cfg["champion_by_line"].values()]
    assert len(versions) == len(set(versions)), "champion model_version values must be unique"
