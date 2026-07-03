"""Materialized quote-ready customer profiles."""
from __future__ import annotations

import datetime
from typing import Iterable

from sqlalchemy.orm import Session

from ..database import CustomerRiskProfile, QuoteReadyProfile
from .claim_history import aggregate_claim_history, enrich_profile_with_claim_history

LINES = ("health", "motorbike", "car", "home", "accident", "travel")


def _now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


def _base_profile(row: CustomerRiskProfile, line: str) -> dict:
    common = dict(row.common_risk_attributes or {})
    by_line = row.line_risk_attributes or {}
    line_attrs = dict(by_line.get(line, {}) if isinstance(by_line, dict) else {})
    profile = dict(common)
    if line_attrs:
        profile["line_attributes"] = line_attrs
    profile["profile_version"] = row.profile_version
    return profile


def rebuild_quote_ready_profile(
    db: Session,
    customer_id: str,
    line: str,
    *,
    last_claim_event_id: str | None = None,
) -> QuoteReadyProfile | None:
    if not customer_id or customer_id == "internal" or line not in LINES:
        return None
    profile_row = db.query(CustomerRiskProfile).filter(CustomerRiskProfile.customer_id == customer_id).first()
    if profile_row is None:
        return None

    base = _base_profile(profile_row, line)
    claim_features = aggregate_claim_history(db, customer_id, line)
    enriched = enrich_profile_with_claim_history(base, claim_features)
    existing = db.query(QuoteReadyProfile).filter(
        QuoteReadyProfile.customer_id == customer_id,
        QuoteReadyProfile.line == line,
    ).first()
    values = {
        "profile_version": profile_row.profile_version,
        "enriched_profile": enriched,
        "claim_features": claim_features,
        "last_profile_event_id": profile_row.last_event_id,
        "last_claim_event_id": last_claim_event_id or (existing.last_claim_event_id if existing else None),
        "updated_at": _now(),
    }
    if existing:
        for key, value in values.items():
            setattr(existing, key, value)
        return existing
    row = QuoteReadyProfile(customer_id=customer_id, line=line, **values)
    db.add(row)
    return row


def rebuild_quote_ready_profiles(
    db: Session,
    customer_id: str,
    lines: Iterable[str] = LINES,
    *,
    last_claim_event_id: str | None = None,
) -> list[QuoteReadyProfile]:
    rows = []
    for line in lines:
        row = rebuild_quote_ready_profile(
            db,
            customer_id,
            line,
            last_claim_event_id=last_claim_event_id,
        )
        if row is not None:
            rows.append(row)
    return rows


def get_quote_ready_profile(db: Session, customer_id: str, line: str) -> dict | None:
    row = db.query(QuoteReadyProfile).filter(
        QuoteReadyProfile.customer_id == customer_id,
        QuoteReadyProfile.line == line,
    ).first()
    if row is None:
        return None
    return dict(row.enriched_profile or {})
