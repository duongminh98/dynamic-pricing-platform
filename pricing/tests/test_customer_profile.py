"""Tests for app.services.customer_profile.merge_customer_risk_profile.

Uses an in-memory SQLite DB; no model artifacts required. Verifies that cached
common + line-specific risk attributes are merged into a quote profile, that
request values win over cached ones, and the internal/no-row short-circuits.
"""
from __future__ import annotations

import datetime

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, CustomerRiskProfile
from app.services.customer_profile import merge_customer_risk_profile


def _session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)()


def _seed(db, customer_id="cust-1", *, common=None, by_line=None, version=2):
    db.add(CustomerRiskProfile(
        customer_id=customer_id,
        profile_version=version,
        effective_at=datetime.datetime.now(datetime.timezone.utc),
        common_risk_attributes=common or {"age": 40, "gender": "Male"},
        line_risk_attributes=by_line or {"health": {"smoker": False, "height_cm": 170}},
        last_event_id="evt-1",
        updated_at=datetime.datetime.now(datetime.timezone.utc),
    ))
    db.commit()


def test_internal_customer_returns_request_profile_unchanged():
    db = _session()
    request = {"age": 33}
    result = merge_customer_risk_profile(db, "internal", "health", request)
    assert result == {"age": 33}
    db.close()


def test_empty_customer_id_returns_request_profile_unchanged():
    db = _session()
    result = merge_customer_risk_profile(db, "", "health", {"age": 33})
    assert result == {"age": 33}
    db.close()


def test_no_cached_row_returns_request_profile_unchanged():
    db = _session()
    result = merge_customer_risk_profile(db, "cust-missing", "health", {"age": 33})
    assert result == {"age": 33}
    db.close()


def test_cached_common_and_line_attrs_fill_missing_fields():
    db = _session()
    _seed(db, "cust-1")
    result = merge_customer_risk_profile(db, "cust-1", "health", {})
    assert result["age"] == 40
    assert result["gender"] == "Male"
    assert result["line_attributes"]["smoker"] is False
    assert result["line_attributes"]["height_cm"] == 170
    assert result["profile_version"] == 2
    db.close()


def test_request_values_win_over_cached_common():
    db = _session()
    _seed(db, "cust-1", common={"age": 40, "gender": "Male"})
    result = merge_customer_risk_profile(db, "cust-1", "health", {"age": 25})
    assert result["age"] == 25  # request wins
    assert result["gender"] == "Male"  # cached fills gap
    db.close()


def test_request_line_attrs_win_over_cached_line_attrs():
    db = _session()
    _seed(db, "cust-1", by_line={"health": {"smoker": False, "height_cm": 170}})
    result = merge_customer_risk_profile(
        db, "cust-1", "health", {"line_attributes": {"smoker": True}}
    )
    assert result["line_attributes"]["smoker"] is True  # request wins
    assert result["line_attributes"]["height_cm"] == 170  # cached fills gap
    db.close()


def test_line_without_cached_attrs_only_merges_common():
    db = _session()
    _seed(db, "cust-1", by_line={"health": {"smoker": False}})
    result = merge_customer_risk_profile(db, "cust-1", "car", {})
    assert result["age"] == 40
    assert "line_attributes" not in result  # no cached car attrs, none supplied
    assert result["profile_version"] == 2
    db.close()
