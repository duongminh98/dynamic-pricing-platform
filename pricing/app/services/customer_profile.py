"""Customer profile read-model helpers for pricing quotes."""
from __future__ import annotations

from typing import Any

from sqlalchemy.orm import Session

from ..database import CustomerRiskProfile


def merge_customer_risk_profile(db: Session, customer_id: str, line: str, request_profile: dict[str, Any]) -> dict[str, Any]:
    """Merge locally cached customer risk attributes into a quote profile.

    Client/request values still win for explicit quote-time attributes. The read-model
    fills missing common and line-specific fields so quote does not need a live
    customer-service call.
    """
    profile = dict(request_profile or {})
    if not customer_id or customer_id == "internal":
        return profile

    row = db.query(CustomerRiskProfile).filter(CustomerRiskProfile.customer_id == customer_id).first()
    if not row:
        return profile

    common = dict(row.common_risk_attributes or {})
    line_attrs_by_line = row.line_risk_attributes or {}
    cached_line_attrs = dict(line_attrs_by_line.get(line, {}) if isinstance(line_attrs_by_line, dict) else {})

    merged = dict(common)
    merged.update(profile)
    merged_line_attrs = dict(cached_line_attrs)
    merged_line_attrs.update(profile.get("line_attributes", {}) or {})
    if merged_line_attrs:
        merged["line_attributes"] = merged_line_attrs
    merged["profile_version"] = row.profile_version
    return merged
