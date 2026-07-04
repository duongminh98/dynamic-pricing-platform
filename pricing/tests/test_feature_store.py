"""Tests for app.feature_store — geo/cost accessors with DB/cache/fallback.

The feature store reads active geo-risk and cost-index reference rows from the
DB read-model, caches them for PRODUCT_CACHE_TTL_SECONDS, and falls back to the
loader's CSV-derived globals when the read-model is empty or the DB is down.
Uses an in-memory SQLite shared across sessions so the module's own
SessionLocal() calls observe seeded rows.
"""
from __future__ import annotations

import datetime

from unittest.mock import patch

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.database import Base, CostIndexReferenceRow, GeoRiskReferenceRow


def _now():
    return datetime.datetime.now(datetime.timezone.utc)


def _shared_sessionmaker():
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)


def _seed_geo(Session, version_id="geo-v1", status="ACTIVE"):
    db = Session()
    db.add(GeoRiskReferenceRow(
        version_id=version_id, province="Ha Noi", region="Red River Delta",
        urban_tier_geo="tier1", traffic_density_score=0.9,
        vehicle_theft_risk_score=0.3, accident_frequency_index=0.5,
        flood_risk_score=0.4, storm_risk_score=0.2, fire_risk_score=0.1,
        crime_risk_score=0.6, healthcare_access_score=0.8, hospital_cost_index=1.1,
        repair_cost_index=1.2, construction_cost_index=1.3,
        status=status, checksum="abc", last_event_id="e1", updated_at=_now(),
    ))
    db.commit()
    db.close()


def _seed_cost(Session, version_id="cost-v1", status="ACTIVE"):
    db = Session()
    db.add(CostIndexReferenceRow(
        version_id=version_id, month_start="2026-07-01", year=2026, month=7,
        medical_inflation_index=1.02, vehicle_repair_inflation_index=1.03,
        construction_inflation_index=1.04, travel_medical_cost_index=1.05,
        general_expense_index=1.06,
        status=status, checksum="xyz", last_event_id="e2", updated_at=_now(),
    ))
    db.commit()
    db.close()


def _fresh_feature_store():
    """Import feature_store and reset its module cache so each test starts cold."""
    import app.feature_store as fs
    fs._geo_cache = {}
    fs._cost_cache = {}
    fs._geo_version_id = None
    fs._cost_version_id = None
    fs._loaded_at = 0.0
    return fs


# ── read-model populated ──

def test_get_cost_indices_from_read_model():
    fs = _fresh_feature_store()
    Session = _shared_sessionmaker()
    _seed_cost(Session)
    with patch("app.feature_store.SessionLocal", Session):
        indices = fs.get_cost_indices()
    assert indices["medical_inflation_index"] == 1.02
    assert indices["general_expense_index"] == 1.06


def test_get_geo_features_from_read_model():
    fs = _fresh_feature_store()
    Session = _shared_sessionmaker()
    _seed_geo(Session)
    with patch("app.feature_store.SessionLocal", Session):
        geo = fs.get_geo_features("Ha Noi")
    assert geo["traffic_density_score"] == 0.9
    assert geo["region"] == "Red River Delta"


def test_get_reference_versions_from_read_model():
    fs = _fresh_feature_store()
    Session = _shared_sessionmaker()
    _seed_geo(Session)
    _seed_cost(Session)
    with patch("app.feature_store.SessionLocal", Session):
        geo_v, cost_v = fs.get_reference_versions()
    assert geo_v == "geo-v1"
    assert cost_v == "cost-v1"


def test_get_geo_features_unknown_province_returns_empty():
    fs = _fresh_feature_store()
    Session = _shared_sessionmaker()
    _seed_geo(Session)
    with patch("app.feature_store.SessionLocal", Session):
        assert fs.get_geo_features("Nowhere") == {}


# ── read-model empty → loader fallback ──

def test_fallback_to_loader_globals_when_read_model_empty():
    fs = _fresh_feature_store()
    Session = _shared_sessionmaker()  # tables exist, no rows
    import app.pricing_engine.loader as loader
    with patch("app.feature_store.SessionLocal", Session), \
         patch.object(loader, "geo_by_province", {"Hue": {"province": "Hue", "flood_risk_score": 0.7}}), \
         patch.object(loader, "cost_indices_latest", {"medical_inflation_index": 1.5}):
        geo = fs.get_geo_features("Hue")
        cost = fs.get_cost_indices()
        geo_v, cost_v = fs.get_reference_versions()
    assert geo["flood_risk_score"] == 0.7
    assert cost["medical_inflation_index"] == 1.5
    # No active read-model rows → version ids are None
    assert geo_v is None and cost_v is None


# ── DB down → loader fallback via except path ──

def test_fallback_to_loader_globals_when_db_errors():
    fs = _fresh_feature_store()
    import app.pricing_engine.loader as loader
    with patch("app.feature_store.SessionLocal", side_effect=Exception("db down")), \
         patch.object(loader, "geo_by_province", {"Hue": {"province": "Hue"}}), \
         patch.object(loader, "cost_indices_latest", {"general_expense_index": 2.0}):
        cost = fs.get_cost_indices()
        geo_v, cost_v = fs.get_reference_versions()
    assert cost["general_expense_index"] == 2.0
    assert geo_v is None and cost_v is None


# ── cache: no refetch within TTL ──

def test_cache_no_refetch_within_ttl():
    fs = _fresh_feature_store()
    Session = _shared_sessionmaker()
    _seed_cost(Session)
    with patch("app.feature_store.SessionLocal", Session):
        fs.get_cost_indices()  # loads + stamps _loaded_at
        # Now point SessionLocal at something that would raise if called again.
        with patch.object(fs, "_refresh", side_effect=AssertionError("should not refresh within TTL")):
            again = fs.get_cost_indices()
    assert again["medical_inflation_index"] == 1.02


# ── clear_cache forces a refresh on next access ──

def test_clear_cache_forces_refresh():
    fs = _fresh_feature_store()
    Session = _shared_sessionmaker()
    _seed_cost(Session)
    with patch("app.feature_store.SessionLocal", Session):
        fs.get_cost_indices()
        fs.clear_cache()
        assert fs._loaded_at == 0.0
