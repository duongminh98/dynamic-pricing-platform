from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, CostIndexReferenceRow, GeoRiskReferenceRow, ProductCatalogItem, ProductLoadingFactor
from app.services.product_read_model import (
    load_active_cost_indices,
    load_active_geo_risk,
    load_loading_factors,
    load_product_catalog,
    upsert_cost_index_version_activated,
    upsert_geo_risk_version_activated,
    upsert_product_catalog_item,
    upsert_rate_version_activated,
)


def _session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    return Session()


def test_product_updated_upserts_product_catalog_item():
    db = _session()
    payload = {
        "event_id": "event-product-1",
        "product_id": "CAR_BASIC",
        "category": "car",
        "product_name": "Car Basic",
        "coverage_amount_vnd": 500000000,
        "deductible_vnd": 1000000,
        "base_premium_vnd": 3000000,
        "admin_fee_vnd": 100000,
        "active": True,
    }

    upsert_product_catalog_item(db, payload)
    db.commit()

    row = db.query(ProductCatalogItem).filter(ProductCatalogItem.product_id == "CAR_BASIC").first()
    assert row is not None
    assert row.category == "car"
    assert row.coverage_amount_vnd == 500000000
    assert row.last_event_id == "event-product-1"
    assert load_product_catalog(db)["CAR_BASIC"]["admin_fee_vnd"] == 100000
    db.close()


def test_rate_version_activated_upserts_loading_factors():
    db = _session()
    payload = {
        "event_id": "event-rate-1",
        "rate_version_id": "rv-1",
        "loading_factors": [
            {"line": "health", "loading_value": 1.2},
            {"line": "car", "loading_value": 1.4},
        ],
    }

    upsert_rate_version_activated(db, payload)
    db.commit()

    health = db.query(ProductLoadingFactor).filter(ProductLoadingFactor.line == "health").first()
    assert health.rate_version_id == "rv-1"
    assert health.loading_value == 1.2
    factors, current_rate_version_id = load_loading_factors(db)
    assert factors == {"health": 1.2, "car": 1.4}
    assert current_rate_version_id == "rv-1"
    db.close()


def test_geo_risk_version_activated_upserts_reference_rows():
    db = _session()
    payload = {
        "event_id": "event-geo-1",
        "version_id": "geo-v1",
        "checksum": "abc",
        "rows": [{
            "province": "Ha Noi",
            "region": "Red River Delta",
            "urban_tier_geo": "tier1",
            "traffic_density_score": 0.9,
            "vehicle_theft_risk_score": 0.3,
            "accident_frequency_index": 0.5,
            "flood_risk_score": 0.4,
            "storm_risk_score": 0.2,
            "fire_risk_score": 0.1,
            "crime_risk_score": 0.6,
            "healthcare_access_score": 0.8,
            "hospital_cost_index": 1.1,
            "repair_cost_index": 1.2,
            "construction_cost_index": 1.3,
        }],
    }

    upsert_geo_risk_version_activated(db, payload)
    db.commit()

    row = db.query(GeoRiskReferenceRow).filter_by(version_id="geo-v1", province="Ha Noi").first()
    assert row is not None
    data, version_id = load_active_geo_risk(db)
    assert version_id == "geo-v1"
    assert data["Ha Noi"]["traffic_density_score"] == 0.9
    db.close()


def test_cost_index_version_activated_upserts_reference_rows():
    db = _session()
    payload = {
        "event_id": "event-cost-1",
        "version_id": "cost-v1",
        "checksum": "xyz",
        "rows": [{
            "year": 2026,
            "month": 7,
            "month_start": "2026-07-01",
            "medical_inflation_index": 1.02,
            "vehicle_repair_inflation_index": 1.03,
            "construction_inflation_index": 1.04,
            "travel_medical_cost_index": 1.05,
            "general_expense_index": 1.06,
        }],
    }

    upsert_cost_index_version_activated(db, payload)
    db.commit()

    row = db.query(CostIndexReferenceRow).filter_by(version_id="cost-v1", month_start="2026-07-01").first()
    assert row is not None
    data, version_id = load_active_cost_indices(db)
    assert version_id == "cost-v1"
    assert data["medical_inflation_index"] == 1.02
    db.close()
