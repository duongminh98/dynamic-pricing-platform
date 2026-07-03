"""Product catalog and pricing reference read-model helpers for pricing."""
from __future__ import annotations

import datetime
from typing import Any

from sqlalchemy.orm import Session

from ..database import (
    CostIndexReferenceRow,
    GeoRiskReferenceRow,
    ProductCatalogItem,
    ProductLoadingFactor,
)

LINES = ("health", "motorbike", "car", "home", "accident", "travel")
ACTIVE = "ACTIVE"


def _now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


def _str_or_none(value: Any) -> str | None:
    return str(value) if value not in (None, "") else None


def _bool_or_default(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "yes", "on")
    return bool(value)


def _int_or_default(value: Any, default: int = 0) -> int:
    if value in (None, ""):
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _float_or_default(value: Any, default: float = 1.0) -> float:
    if value in (None, ""):
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _event_id(payload: dict) -> str | None:
    return _str_or_none(payload.get("event_id") or payload.get("id"))


def product_row_to_dict(row: ProductCatalogItem) -> dict:
    return {
        "product_id": row.product_id,
        "category": row.category,
        "product_name": row.product_name,
        "coverage_amount_vnd": int(row.coverage_amount_vnd or 0),
        "deductible_vnd": int(row.deductible_vnd or 0),
        "base_premium_vnd": int(row.base_premium_vnd or 0),
        "admin_fee_vnd": int(row.admin_fee_vnd or 0),
        "active": bool(row.active),
    }


def load_product_catalog(db: Session, active_only: bool = False) -> dict[str, dict]:
    query = db.query(ProductCatalogItem)
    if active_only:
        query = query.filter(ProductCatalogItem.active.is_(True))
    return {row.product_id: product_row_to_dict(row) for row in query.all()}


def load_loading_factors(db: Session) -> tuple[dict[str, float], str | None]:
    rows = db.query(ProductLoadingFactor).all()
    factors = {row.line: float(row.loading_value) for row in rows}
    current_rate_version_id = next((row.rate_version_id for row in rows if row.rate_version_id), None)
    return factors, current_rate_version_id


def load_active_geo_risk(db: Session) -> tuple[dict[str, dict], str | None]:
    rows = db.query(GeoRiskReferenceRow).filter(GeoRiskReferenceRow.status == ACTIVE).all()
    data = {}
    version_id = None
    for row in rows:
        version_id = row.version_id
        data[row.province] = {
            "province": row.province,
            "region": row.region,
            "urban_tier_geo": row.urban_tier_geo,
            "traffic_density_score": float(row.traffic_density_score or 0.0),
            "vehicle_theft_risk_score": float(row.vehicle_theft_risk_score or 0.0),
            "accident_frequency_index": float(row.accident_frequency_index or 0.0),
            "flood_risk_score": float(row.flood_risk_score or 0.0),
            "storm_risk_score": float(row.storm_risk_score or 0.0),
            "fire_risk_score": float(row.fire_risk_score or 0.0),
            "crime_risk_score": float(row.crime_risk_score or 0.0),
            "healthcare_access_score": float(row.healthcare_access_score or 0.0),
            "hospital_cost_index": float(row.hospital_cost_index or 0.0),
            "repair_cost_index": float(row.repair_cost_index or 0.0),
            "construction_cost_index": float(row.construction_cost_index or 0.0),
        }
    return data, version_id


def load_active_cost_indices(db: Session) -> tuple[dict[str, float], str | None]:
    rows = db.query(CostIndexReferenceRow).filter(CostIndexReferenceRow.status == ACTIVE).all()
    if not rows:
        return {}, None
    latest = sorted(rows, key=lambda row: row.month_start)[-1]
    return {
        "medical_inflation_index": float(latest.medical_inflation_index or 1.0),
        "vehicle_repair_inflation_index": float(latest.vehicle_repair_inflation_index or 1.0),
        "construction_inflation_index": float(latest.construction_inflation_index or 1.0),
        "travel_medical_cost_index": float(latest.travel_medical_cost_index or 1.0),
        "general_expense_index": float(latest.general_expense_index or 1.0),
    }, latest.version_id


def upsert_product_catalog_item(db: Session, payload: dict) -> None:
    product_id = _str_or_none(payload.get("product_id"))
    category = _str_or_none(payload.get("category") or payload.get("line"))
    if not product_id or not category:
        raise ValueError("ProductUpdated event requires product_id and category")

    existing = db.query(ProductCatalogItem).filter(ProductCatalogItem.product_id == product_id).first()
    values = {
        "category": category,
        "product_name": _str_or_none(payload.get("product_name")),
        "coverage_amount_vnd": _int_or_default(payload.get("coverage_amount_vnd")),
        "deductible_vnd": _int_or_default(payload.get("deductible_vnd")),
        "base_premium_vnd": _int_or_default(payload.get("base_premium_vnd")),
        "admin_fee_vnd": _int_or_default(payload.get("admin_fee_vnd")),
        "active": _bool_or_default(payload.get("active"), True),
        "last_event_id": _event_id(payload),
        "updated_at": _now(),
    }
    if existing:
        for key, value in values.items():
            setattr(existing, key, value)
    else:
        db.add(ProductCatalogItem(product_id=product_id, **values))


def upsert_rate_version_activated(db: Session, payload: dict) -> None:
    rate_version_id = _str_or_none(payload.get("rate_version_id"))
    event_id = _event_id(payload)
    loading_factors = payload.get("loading_factors")
    rows: list[dict[str, Any]] = []

    if isinstance(loading_factors, list):
        rows = [row for row in loading_factors if isinstance(row, dict)]
    elif payload.get("line") is not None:
        rows = [{"line": payload.get("line"), "loading_value": payload.get("loading_value")}]

    if not rows:
        rows = [{"line": line, "loading_value": 1.0} for line in LINES]

    now = _now()
    for row in rows:
        line = _str_or_none(row.get("line"))
        if not line:
            continue
        existing = db.query(ProductLoadingFactor).filter(ProductLoadingFactor.line == line).first()
        values = {
            "rate_version_id": _str_or_none(row.get("rate_version_id")) or rate_version_id,
            "loading_value": _float_or_default(row.get("loading_value"), 1.0),
            "last_event_id": event_id,
            "updated_at": now,
        }
        if existing:
            for key, value in values.items():
                setattr(existing, key, value)
        else:
            db.add(ProductLoadingFactor(line=line, **values))


def upsert_geo_risk_version_activated(db: Session, payload: dict) -> None:
    version_id = _str_or_none(payload.get("version_id"))
    if not version_id:
        raise ValueError("GeoRiskVersionActivated event requires version_id")
    event_id = _event_id(payload)
    checksum = _str_or_none(payload.get("checksum"))
    rows = payload.get("rows") or []
    db.query(GeoRiskReferenceRow).update({GeoRiskReferenceRow.status: "RETIRED"})
    now = _now()
    for row in rows:
        province = _str_or_none(row.get("province"))
        if not province:
            continue
        db.merge(GeoRiskReferenceRow(
            version_id=version_id,
            province=province,
            region=_str_or_none(row.get("region")),
            urban_tier_geo=_str_or_none(row.get("urban_tier_geo")),
            traffic_density_score=_float_or_default(row.get("traffic_density_score"), 0.0),
            vehicle_theft_risk_score=_float_or_default(row.get("vehicle_theft_risk_score"), 0.0),
            accident_frequency_index=_float_or_default(row.get("accident_frequency_index"), 0.0),
            flood_risk_score=_float_or_default(row.get("flood_risk_score"), 0.0),
            storm_risk_score=_float_or_default(row.get("storm_risk_score"), 0.0),
            fire_risk_score=_float_or_default(row.get("fire_risk_score"), 0.0),
            crime_risk_score=_float_or_default(row.get("crime_risk_score"), 0.0),
            healthcare_access_score=_float_or_default(row.get("healthcare_access_score"), 0.0),
            hospital_cost_index=_float_or_default(row.get("hospital_cost_index"), 0.0),
            repair_cost_index=_float_or_default(row.get("repair_cost_index"), 0.0),
            construction_cost_index=_float_or_default(row.get("construction_cost_index"), 0.0),
            status=ACTIVE,
            checksum=checksum,
            last_event_id=event_id,
            updated_at=now,
        ))


def upsert_cost_index_version_activated(db: Session, payload: dict) -> None:
    version_id = _str_or_none(payload.get("version_id"))
    if not version_id:
        raise ValueError("CostIndexVersionActivated event requires version_id")
    event_id = _event_id(payload)
    checksum = _str_or_none(payload.get("checksum"))
    rows = payload.get("rows") or []
    db.query(CostIndexReferenceRow).update({CostIndexReferenceRow.status: "RETIRED"})
    now = _now()
    for row in rows:
        month_start = _str_or_none(row.get("month_start"))
        if not month_start:
            continue
        db.merge(CostIndexReferenceRow(
            version_id=version_id,
            month_start=month_start,
            year=_int_or_default(row.get("year"), 0),
            month=_int_or_default(row.get("month"), 0),
            medical_inflation_index=_float_or_default(row.get("medical_inflation_index"), 1.0),
            vehicle_repair_inflation_index=_float_or_default(row.get("vehicle_repair_inflation_index"), 1.0),
            construction_inflation_index=_float_or_default(row.get("construction_inflation_index"), 1.0),
            travel_medical_cost_index=_float_or_default(row.get("travel_medical_cost_index"), 1.0),
            general_expense_index=_float_or_default(row.get("general_expense_index"), 1.0),
            status=ACTIVE,
            checksum=checksum,
            last_event_id=event_id,
            updated_at=now,
        ))
