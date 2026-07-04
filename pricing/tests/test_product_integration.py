"""Tests for product-service integration in the pricing loader.

The loader no longer parses a product-service HTTP wire format; it reads a
DB-backed read-model (product_catalog_item / product_loading_factor) populated
by event consumers, and falls back to products.csv when the read-model is
empty or unavailable. The read-model service itself is covered by
test_product_read_model.py; here we cover the loader-level integration:

- _load_products: read-model first, CSV fallback, empty when neither present
- _load_loading_factors: read-model first, 1.0 defaults when empty
- get_loading_factor / get_current_rate_version_id accessors
- Cache/TTL: no refetch within TTL, refetch after expiry
- Quote reflects loading_factor and admin_fee changes (compute_final_premium)
"""
from __future__ import annotations

import datetime
import time
from unittest.mock import patch

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.database import Base, ProductCatalogItem, ProductLoadingFactor


@pytest.fixture(autouse=True)
def _restore_loader_state():
    """These tests mutate loader module globals (products, loading_factors,
    _loaded, cache timestamps) directly. Snapshot and restore them so the
    session-loaded artifact state other tests rely on is not corrupted."""
    import app.pricing_engine.loader as loader
    saved = {
        name: getattr(loader, name)
        for name in (
            "products", "loading_factors", "current_rate_version_id",
            "_loaded", "_products_loaded_at", "_loading_loaded_at",
        )
    }
    try:
        yield
    finally:
        for name, value in saved.items():
            setattr(loader, name, value)


def _now():
    return datetime.datetime.now(datetime.timezone.utc)


def _shared_sessionmaker():
    """In-memory SQLite shared across sessions (StaticPool = single connection),
    so the loader's own SessionLocal() calls observe seeded rows."""
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)


def _seed_products(Session):
    db = Session()
    db.add(ProductCatalogItem(
        product_id="HEALTH_BASIC", category="health", product_name="Basic Health",
        coverage_amount_vnd=50_000_000, deductible_vnd=200_000,
        base_premium_vnd=800_000, admin_fee_vnd=15_000, active=True,
        last_event_id="evt-1", updated_at=_now(),
    ))
    db.commit()
    db.close()


def _seed_loading_factors(Session):
    db = Session()
    db.add(ProductLoadingFactor(
        line="health", rate_version_id="rv-abc-123", loading_value=1.3,
        last_event_id="evt-rate-1", updated_at=_now(),
    ))
    db.add(ProductLoadingFactor(
        line="motorbike", rate_version_id="rv-abc-123", loading_value=1.0,
        last_event_id="evt-rate-1", updated_at=_now(),
    ))
    db.commit()
    db.close()


# ── _load_products: read-model first ──

def test_load_products_from_read_model():
    import app.pricing_engine.loader as loader
    Session = _shared_sessionmaker()
    _seed_products(Session)
    with patch("app.database.SessionLocal", Session):
        loader.products = {}
        loader._load_products()
    assert "HEALTH_BASIC" in loader.products
    assert loader.products["HEALTH_BASIC"]["category"] == "health"
    assert loader.products["HEALTH_BASIC"]["admin_fee_vnd"] == 15_000


# ── Fallback: read-model empty → CSV ──

def test_load_products_fallback_to_csv(tmp_path):
    import app.pricing_engine.loader as loader
    Session = _shared_sessionmaker()  # tables exist but no rows → read-model empty
    csv_content = (
        "product_id,category,product_name,coverage_amount_vnd,deductible_vnd,"
        "base_premium_vnd,admin_fee_vnd,active\n"
        "health-csv,health,CSV Health,50000000,200000,800000,15000,True\n"
    )
    csv_file = tmp_path / "products.csv"
    csv_file.write_text(csv_content)

    with patch("app.database.SessionLocal", Session), \
         patch.object(loader, "PRODUCTS_PATH", csv_file):
        loader.products = {}
        loader._load_products()

    assert "health-csv" in loader.products
    assert loader.products["health-csv"]["category"] == "health"


def test_load_products_empty_when_no_db_no_csv():
    import pathlib
    import app.pricing_engine.loader as loader
    with patch("app.database.SessionLocal", side_effect=Exception("connection refused")), \
         patch.object(loader, "PRODUCTS_PATH", pathlib.Path("/nonexistent/products.csv")):
        loader.products = {}
        loader._load_products()
    assert loader.products == {}


# ── _load_loading_factors: read-model first ──

def test_load_loading_factors_from_read_model():
    import app.pricing_engine.loader as loader
    Session = _shared_sessionmaker()
    _seed_loading_factors(Session)
    with patch("app.database.SessionLocal", Session):
        loader.loading_factors = {}
        loader.current_rate_version_id = None
        loader._load_loading_factors()
    assert loader.loading_factors["health"] == 1.3
    assert loader.current_rate_version_id == "rv-abc-123"


def test_load_loading_factors_defaults_when_empty():
    import app.pricing_engine.loader as loader
    with patch("app.database.SessionLocal", side_effect=Exception("fail")):
        loader.loading_factors = {}
        loader._load_loading_factors()
    # Falls back to 1.0 for every known line
    assert loader.loading_factors == {ln: 1.0 for ln in loader.LINES}


# ── get_loading_factor fallback ──

def test_get_loading_factor_returns_1_when_not_loaded():
    import app.pricing_engine.loader as loader
    loader.loading_factors = {}
    loader._loaded = True  # skip full load
    loader._loading_loaded_at = time.monotonic()
    with patch("app.database.SessionLocal", side_effect=Exception("fail")):
        result = loader.get_loading_factor("health")
    assert result == 1.0


# ── Cache/TTL: no re-fetch within TTL ──

def test_cache_ttl_no_refetch_within_window():
    import app.pricing_engine.loader as loader
    loader._loaded = True
    loader.loading_factors = {"health": 1.3}
    loader._loading_loaded_at = time.monotonic()  # just loaded
    with patch.object(loader, "_load_loading_factors") as mock_reload:
        loader.get_loading_factor("health")
        assert mock_reload.call_count == 0  # within TTL, no refresh


def test_cache_ttl_refetch_after_expiry():
    import app.pricing_engine.loader as loader
    loader._loaded = True
    loader.loading_factors = {"health": 1.0}
    loader._loading_loaded_at = time.monotonic() - 999  # far past → force refresh
    with patch.object(loader, "_load_loading_factors") as mock_reload:
        loader.get_loading_factor("health")
        assert mock_reload.call_count == 1


# ── current_rate_version_id from read-model ──

def test_current_rate_version_id_set_from_loading_factors():
    import app.pricing_engine.loader as loader
    Session = _shared_sessionmaker()
    _seed_loading_factors(Session)
    with patch("app.database.SessionLocal", Session):
        loader.loading_factors = {}
        loader.current_rate_version_id = None
        loader._load_loading_factors()
        loader._loaded = True
    assert loader.current_rate_version_id == "rv-abc-123"
    assert loader.get_current_rate_version_id() == "rv-abc-123"


# ── compute_final_premium with loading factor / admin fee ──

def test_compute_final_premium_loading_factor():
    from app.pricing_engine.engine import compute_final_premium
    pure, final = compute_final_premium(1000.0, 1.0, 5000)
    assert pure == 1000
    assert final == 6000  # 1000*1.0 + 5000

    pure, final = compute_final_premium(1000.0, 1.5, 5000)
    assert pure == 1000
    assert final == 6500  # 1000*1.5 + 5000


def test_compute_final_premium_admin_fee_change():
    from app.pricing_engine.engine import compute_final_premium
    pure1, final1 = compute_final_premium(1000.0, 1.0, 5000)
    pure2, final2 = compute_final_premium(1000.0, 1.0, 10000)
    assert pure1 == pure2  # pure_premium unchanged
    assert final2 - final1 == 5000  # admin_fee difference


# ── get_loading_factor returns cached value ──

def test_get_loading_factor_returns_cached_value():
    import app.pricing_engine.loader as loader
    loader._loaded = True
    loader.loading_factors = {"health": 1.3, "motorbike": 0.9}
    loader._loading_loaded_at = time.monotonic()

    assert loader.get_loading_factor("health") == 1.3
    assert loader.get_loading_factor("motorbike") == 0.9
    assert loader.get_loading_factor("unknown_line") == 1.0
