"""Feature store accessors for geo risk + cost indices with DB/cache/fallback."""
from __future__ import annotations

import time
from typing import Tuple

from ..config import PRODUCT_CACHE_TTL_SECONDS
from ..database import SessionLocal
from ..services.product_read_model import load_active_cost_indices, load_active_geo_risk
_geo_cache: dict[str, dict] = {}
_cost_cache: dict[str, float] = {}
_geo_version_id: str | None = None
_cost_version_id: str | None = None
_loaded_at = 0.0


def _refresh() -> None:
    global _geo_cache, _cost_cache, _geo_version_id, _cost_version_id, _loaded_at
    try:
        db = SessionLocal()
        try:
            geo, geo_version_id = load_active_geo_risk(db)
            cost, cost_version_id = load_active_cost_indices(db)
        finally:
            db.close()
        if geo:
            _geo_cache = geo
            _geo_version_id = geo_version_id
        else:
            from ..pricing_engine import loader
            _geo_cache = dict(loader.geo_by_province)
            _geo_version_id = None
        if cost:
            _cost_cache = cost
            _cost_version_id = cost_version_id
        else:
            from ..pricing_engine import loader
            _cost_cache = dict(loader.cost_indices_latest)
            _cost_version_id = None
    except Exception:
        from ..pricing_engine import loader
        _geo_cache = dict(loader.geo_by_province)
        _cost_cache = dict(loader.cost_indices_latest)
        _geo_version_id = None
        _cost_version_id = None
    _loaded_at = time.monotonic()


def _ensure() -> None:
    if time.monotonic() - _loaded_at > PRODUCT_CACHE_TTL_SECONDS:
        _refresh()


def get_geo_features(province: str) -> dict:
    _ensure()
    return dict(_geo_cache.get(province, {}))


def get_cost_indices() -> dict[str, float]:
    _ensure()
    return dict(_cost_cache)


def get_reference_versions() -> Tuple[str | None, str | None]:
    _ensure()
    return _geo_version_id, _cost_version_id


def clear_cache() -> None:
    global _loaded_at
    _loaded_at = 0.0

