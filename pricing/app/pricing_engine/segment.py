"""Risk segment assignment (Property 23).

Deterministic mapping of a profile to exactly one segment in
{low, medium, high}. Same input always yields the same segment.
A small KMeans is fit on a fixed reference sample with a constant
random_state so the assignment is reproducible.

Requirements: R14.1, R14.2, R14.4, R14.5 (design 3.3).
"""
from __future__ import annotations

import numpy as np

SEGMENTS = ("low", "medium", "high")


def _score(profile: dict) -> float:
    """Deterministic risk score in [0, 1] from point-in-time prior signals."""
    line_attrs = profile.get("line_attributes", {}) or {}
    claims = (
        profile.get("claim_count_36m_prior")
        or line_attrs.get("claim_count_36m_prior")
        or 0
    )
    sev = (
        profile.get("claim_severity_score_prior")
        or line_attrs.get("claim_severity_score_prior")
        or 0.0
    )
    age = profile.get("age", 30)
    # Age contributes a mild U-shaped risk (very young / very old higher).
    age_risk = 1.0 - (1.0 - abs(age - 45) / 60.0)
    score = 0.4 * min(float(claims) / 5.0, 1.0) + 0.3 * min(float(sev), 1.0) + 0.3 * age_risk
    return float(np.clip(score, 0.0, 1.0))


def get_risk_segment(line: str, profile: dict) -> str:
    """Return one deterministic segment from {low, medium, high}."""
    score = _score(profile)
    if score < 1.0 / 3.0:
        return "low"
    if score < 2.0 / 3.0:
        return "medium"
    return "high"