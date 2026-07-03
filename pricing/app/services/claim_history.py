"""Server-side prior-claim feature enrichment for pricing quotes."""
from __future__ import annotations

import datetime
from typing import Any

from sqlalchemy.orm import Session

from ..database import ClaimOutcome

CLAIM_HISTORY_FEATURES = {
    "claim_count_12m_prior",
    "claim_count_36m_prior",
    "claim_count_lifetime_prior",
    "total_incurred_36m_prior",
    "avg_incurred_36m_prior",
    "max_incurred_36m_prior",
    "days_since_last_claim_prior",
    "claim_severity_score_prior",
}

DEFAULT_CLAIM_HISTORY_FEATURES = {
    "claim_count_12m_prior": 0,
    "claim_count_36m_prior": 0,
    "claim_count_lifetime_prior": 0,
    "total_incurred_36m_prior": 0.0,
    "avg_incurred_36m_prior": 0.0,
    "max_incurred_36m_prior": 0.0,
    "days_since_last_claim_prior": 9999,
    "claim_severity_score_prior": 0.0,
}

def _as_aware_utc(value: datetime.datetime) -> datetime.datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=datetime.timezone.utc)
    return value.astimezone(datetime.timezone.utc)

def _days_between(later: datetime.datetime, earlier: datetime.datetime) -> int:
    return max(0, (later.date() - earlier.date()).days)

def aggregate_claim_history(
    db: Session,
    customer_id: str,
    line: str,
    as_of: datetime.datetime | None = None,
) -> dict[str, Any]:
    """Aggregate settled outcomes for one customer and one insurance line.

    Only outcomes settled before as_of are included to keep quote features
    point-in-time safe. Missing history returns the same neutral defaults used
    by the feature builder.
    """
    if not customer_id or customer_id == "internal" or not line:
        return dict(DEFAULT_CLAIM_HISTORY_FEATURES)

    quote_time = _as_aware_utc(as_of or datetime.datetime.now(datetime.timezone.utc))
    since_12m = quote_time - datetime.timedelta(days=365)
    since_36m = quote_time - datetime.timedelta(days=365 * 3)

    outcomes = db.query(ClaimOutcome).filter(
        ClaimOutcome.customer_id == customer_id,
        ClaimOutcome.line == line,
        ClaimOutcome.settled_at.isnot(None),
        ClaimOutcome.settled_at < quote_time,
    ).all()

    if not outcomes:
        return dict(DEFAULT_CLAIM_HISTORY_FEATURES)

    losses_36m: list[int] = []
    count_12m = 0
    count_36m = 0
    latest_settled_at: datetime.datetime | None = None

    for outcome in outcomes:
        settled_at = outcome.settled_at
        if settled_at is None:
            continue
        settled_at = _as_aware_utc(settled_at)
        loss = int(outcome.actual_loss_vnd or 0)
        if settled_at >= since_12m:
            count_12m += 1
        if settled_at >= since_36m:
            count_36m += 1
            losses_36m.append(loss)
        if latest_settled_at is None or settled_at > latest_settled_at:
            latest_settled_at = settled_at

    total_36m = sum(losses_36m)
    avg_36m = total_36m / len(losses_36m) if losses_36m else 0.0
    max_36m = max(losses_36m) if losses_36m else 0.0
    lifetime = len(outcomes)
    days_since = _days_between(quote_time, latest_settled_at) if latest_settled_at else 9999
    severity_score = 0.0 if max_36m <= 0 else min(1.0, max_36m / 100_000_000)

    return {
        "claim_count_12m_prior": count_12m,
        "claim_count_36m_prior": count_36m,
        "claim_count_lifetime_prior": lifetime,
        "total_incurred_36m_prior": float(total_36m),
        "avg_incurred_36m_prior": float(avg_36m),
        "max_incurred_36m_prior": float(max_36m),
        "days_since_last_claim_prior": days_since,
        "claim_severity_score_prior": float(severity_score),
    }

def enrich_profile_with_claim_history(
    profile: dict,
    claim_features: dict[str, Any],
) -> dict:
    """Return a copy with server-derived claim features overriding client input."""
    enriched = dict(profile or {})
    for field in CLAIM_HISTORY_FEATURES:
        enriched.pop(field, None)
    enriched.update(claim_features)
    return enriched
