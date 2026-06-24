"""Smoke test: engine loads and quotes deterministically."""
from app.pricing_engine.engine import quote


def test_quote_returns_vnd():
    prof = {"age": 30, "gender": "Male", "province": "Ha Noi",
            "region": "Red River Delta", "urban_tier": "tier1",
            "occupation": "engineer", "income_level": "middle",
            "marital_status": "single",
            "line_attributes": {"smoker": False, "height_cm": 170, "weight_kg": 65}}
    r = quote(None, "HEALTH_BASIC", prof)
    assert r["currency"] == "VND"
    assert r["pure_premium_vnd"] >= 0
    assert r["final_premium_vnd"] >= 0