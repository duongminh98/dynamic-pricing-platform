"""Tests for product-service integration in pricing loader + engine.

Covers:
- _product_json_to_row snake_case wire mapping
- _load_products fallback to CSV when httpx fails
- get_loading_factor returns 1.0 as fallback
- Cache/TTL: multiple calls don't re-fetch within TTL; refresh after TTL
- Quote reflects loading_factor and admin_fee changes
- product_rate_version_id appears in quote response
"""
from __future__ import annotations

import importlib
import time
from unittest.mock import patch, MagicMock

import pytest


# ── _product_json_to_row mapping ──

def test_product_json_to_row_maps_snake_case():
    from app.pricing_engine.loader import _product_json_to_row
    raw = {
        "product_id": "health-basic",
        "category": "health",
        "product_name": "Basic Health",
        "coverage_amount_vnd": 50000000,
        "deductible_vnd": 200000,
        "base_premium_vnd": 800000,
        "admin_fee_vnd": 15000,
        "active": True,
    }
    row = _product_json_to_row(raw)
    assert row["product_id"] == "health-basic"
    assert row["category"] == "health"
    assert row["product_name"] == "Basic Health"
    assert row["coverage_amount_vnd"] == 50000000
    assert row["deductible_vnd"] == 200000
    assert row["base_premium_vnd"] == 800000
    assert row["admin_fee_vnd"] == 15000
    assert row["active"] is True


def test_product_json_to_row_rejects_camelcase():
    from app.pricing_engine.loader import _product_json_to_row
    with pytest.raises(KeyError):
        _product_json_to_row({"productId": "x", "category": "health"})


def test_product_json_to_row_defaults():
    from app.pricing_engine.loader import _product_json_to_row
    raw = {"product_id": "p1", "category": "car"}
    row = _product_json_to_row(raw)
    assert row["product_name"] is None
    assert row["coverage_amount_vnd"] == 0
    assert row["deductible_vnd"] == 0
    assert row["base_premium_vnd"] == 0
    assert row["admin_fee_vnd"] == 0
    assert row["active"] is True


# ── Fallback: httpx error → CSV ──

def test_load_products_fallback_to_csv(tmp_path):
    import app.pricing_engine.loader as loader
    csv_content = "product_id,category,product_name,coverage_amount_vnd,deductible_vnd,base_premium_vnd,admin_fee_vnd,active\n"
    csv_content += "health-csv,health,CSV Health,50000000,200000,800000,15000,True\n"
    csv_file = tmp_path / "products.csv"
    csv_file.write_text(csv_content)

    with patch.object(loader, "PRODUCTS_PATH", csv_file), \
         patch.object(loader, "PRODUCT_SERVICE_BASE_URL", "http://nonexistent:9999"), \
         patch.object(loader, "PRODUCT_HTTP_TIMEOUT_SECONDS", 0.5):
        loader._load_products()

    assert "health-csv" in loader.products
    assert loader.products["health-csv"]["category"] == "health"


def test_load_products_no_crash_on_error():
    import pathlib
    import app.pricing_engine.loader as loader
    with patch("app.pricing_engine.loader.httpx.get", side_effect=Exception("connection refused")), \
         patch.object(loader, "PRODUCTS_PATH", pathlib.Path("/nonexistent/path")):
        loader._load_products()
    # products should be empty dict (no CSV either)
    assert loader.products == {}


# ── get_loading_factor fallback ──

def test_get_loading_factor_returns_1_when_not_loaded():
    import app.pricing_engine.loader as loader
    # Reset loading_factors to empty
    loader.loading_factors = {}
    loader._loaded = True  # skip full load
    loader._loading_loaded_at = time.monotonic()
    with patch("app.pricing_engine.loader.httpx.get", side_effect=Exception("fail")):
        result = loader.get_loading_factor("health")
    assert result == 1.0


# ── Cache/TTL: no re-fetch within TTL ──

def test_cache_ttl_no_refetch_within_window():
    import app.pricing_engine.loader as loader
    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json = MagicMock(return_value=[
        {"line": "health", "loading_value": 1.3, "rate_version_id": "rv-123"},
    ])
    loader._loaded = True
    loader.loading_factors = {}
    loader._loading_loaded_at = time.monotonic()  # just loaded

    with patch("app.pricing_engine.loader.httpx.get", return_value=mock_response) as mock_get:
        loader.get_loading_factor("health")
        assert mock_get.call_count == 0  # within TTL, no refresh


def test_cache_ttl_refetch_after_expiry():
    import app.pricing_engine.loader as loader
    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json = MagicMock(return_value=[
        {"line": "health", "loading_value": 1.5, "rate_version_id": "rv-456"},
    ])
    loader._loaded = True
    loader.loading_factors = {"health": 1.0}
    # Set loaded_at far in the past to force refresh
    loader._loading_loaded_at = time.monotonic() - 999

    with patch("app.pricing_engine.loader.httpx.get", return_value=mock_response) as mock_get:
        result = loader.get_loading_factor("health")
        assert mock_get.call_count == 1
        assert result == 1.5


# ── product_rate_version_id in response ──

def test_current_rate_version_id_set_from_loading_factors():
    import app.pricing_engine.loader as loader
    mock_response = MagicMock()
    mock_response.raise_for_status = MagicMock()
    mock_response.json = MagicMock(return_value=[
        {"line": "health", "loading_value": 1.2, "rate_version_id": "rv-abc-123"},
        {"line": "motorbike", "loading_value": 1.0, "rate_version_id": "rv-abc-123"},
    ])
    loader._loaded = True
    loader.loading_factors = {}
    loader._loading_loaded_at = time.monotonic() - 999

    with patch("app.pricing_engine.loader.httpx.get", return_value=mock_response):
        loader._load_loading_factors()

    assert loader.current_rate_version_id == "rv-abc-123"
    assert loader.get_current_rate_version_id() == "rv-abc-123"


# ── compute_final_premium with loading factor ──

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


# ── get_loading_factor + get_current_rate_version_id integration ──

def test_get_loading_factor_returns_cached_value():
    import app.pricing_engine.loader as loader
    loader._loaded = True
    loader.loading_factors = {"health": 1.3, "motorbike": 0.9}
    loader._loading_loaded_at = time.monotonic()

    assert loader.get_loading_factor("health") == 1.3
    assert loader.get_loading_factor("motorbike") == 0.9
    assert loader.get_loading_factor("unknown_line") == 1.0
