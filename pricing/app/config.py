"""Bonus feature flags for the Pricing Service.

Per the refined scope (design section 1.6), per-quote audit (R35), validation
reports (R20) and fairness checks (R13) are bonus features gated behind
configuration flags. Both default to OFF so the core quote path stays lean.

Flags are read from environment variables so tests and live deployments can
toggle them without code changes:
    PRICING_BONUS_QUOTE_AUDIT_ENABLED            (default: false)
    PRICING_BONUS_VALIDATION_ENDPOINTS_ENABLED   (default: false)
"""
from __future__ import annotations

import os


def _bool_env(name: str, default: bool = False) -> bool:
    value = os.environ.get(name, "").strip().lower()
    if value in ("1", "true", "yes", "on"):
        return True
    if value in ("0", "false", "no", "off"):
        return False
    return default


# Bonus: per-quote Audit_Trail writes (R35). Default OFF.
QUOTE_AUDIT_ENABLED = _bool_env("PRICING_BONUS_QUOTE_AUDIT_ENABLED", False)

# Bonus: GET /pricing/validation/{line} + GET /pricing/fairness/{line} (R20/R13).
# Default OFF; when OFF the endpoints return 404 VALIDATION_REPORT_UNAVAILABLE.
VALIDATION_ENDPOINTS_ENABLED = _bool_env(
    "PRICING_BONUS_VALIDATION_ENDPOINTS_ENABLED", False)
