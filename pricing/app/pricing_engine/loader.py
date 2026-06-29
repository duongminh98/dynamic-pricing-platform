"""Artifact + lookup loader for the Pricing engine.

Loads the 36 model artifacts (6 lines x 3 families x 2 algorithms),
champion_config.json, modeling metadata, geo risk lookup, cost indices
and the product catalog at startup. Product catalog is loaded from
product-service at startup with TTL refresh; products.csv is fallback
+ offline training only. Fail-fast on missing required files
(R11.2, R11.3). Feature discovery excludes leakage columns (R29.1).

Requirements: R11.2, R11.3, R11.4, R29.1, R29.3, R29.4 (design 6.1, 5.8).
"""
from __future__ import annotations

import json
import pathlib
import time
import warnings

import httpx

import sys as _sys
import sklearn._loss._loss as _skloss
# Compatibility shim: some LightGBM artifacts were pickled with an older
# scikit-learn that stored the loss module as the top-level name `_loss`.
# scikit-learn >=1.4 moved it under `sklearn._loss._loss`; alias the old
# import path so joblib.load can unpickle the models (R11.4).
_sys.modules.setdefault("_loss", _skloss)

import joblib
import pandas as pd

from ..config import (
    PRODUCT_SERVICE_BASE_URL, PRODUCT_HTTP_TIMEOUT_SECONDS, PRODUCT_CACHE_TTL_SECONDS,
)

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
MODELS_DIR = ROOT / "reports" / "modeling" / "models"
DATA_DIR = ROOT / "data" / "synthetic_real"
METADATA_PATH = DATA_DIR / "pricing_modeling_metadata.json"
GEO_RISK_PATH = DATA_DIR / "geo_risk.csv"
COST_INDICES_PATH = DATA_DIR / "cost_indices.csv"
PRODUCTS_PATH = DATA_DIR / "products.csv"

LINES = ["health", "motorbike", "car", "home", "accident", "travel"]
FAMILIES = ["freq", "sev", "tw"]

# Columns that must never be used as model features (leakage / targets / ids).
LEAKAGE_COLS = {
    "exposure_id", "policy_id", "claim_id", "customer_id", "unit_id",
    "policy_effective_date", "policy_expiration_date", "occurrence_date",
    "report_date", "earned_exposure_years", "offset_log_exposure",
    "policy_year", "incurred_amount", "paid_amount", "claim_status",
    "severity_level", "freq_rate", "sev_base", "claim_count", "claim_flag",
    "final_premium_vnd", "exposure_segment_seq", "line",
}

artifacts: dict[str, dict[str, object]] = {}
all_artifacts: dict[str, dict[str, dict[str, object]]] = {}
champion_config: dict = {}
metadata: dict = {}
geo_by_province: dict[str, dict] = {}
cost_indices_latest: dict[str, float] = {}
products: dict[str, dict] = {}
loading_factors: dict[str, float] = {}
current_rate_version_id: str | None = None
_loaded = False
_products_loaded_at = 0.0
_loading_loaded_at = 0.0


def _algorithm_for(line: str) -> str:
    cfg = champion_config.get("champion_by_line", {}).get(line, {})
    return cfg.get("algorithm", "lgb")


def load_artifacts() -> None:
    """Load all artifacts + lookups. Idempotent; fail-fast on missing config."""
    global artifacts, all_artifacts, champion_config, metadata
    global geo_by_province, cost_indices_latest, products, _loaded

    config_path = MODELS_DIR / "champion_config.json"
    if not config_path.exists():
        raise RuntimeError("champion_config.json not found")
    with open(config_path, encoding="utf-8") as f:
        champion_config = json.load(f)

    if not METADATA_PATH.exists():
        raise RuntimeError("pricing_modeling_metadata.json not found")
    with open(METADATA_PATH, encoding="utf-8") as f:
        metadata = json.load(f)

    artifacts = {}
    all_artifacts = {}
    for line in LINES:
        algo = _algorithm_for(line)
        artifacts[line] = {}
        all_artifacts[line] = {}
        for family in FAMILIES:
            all_artifacts[line][family] = {}
            for cand in (algo, "lgb", "glm"):
                path = MODELS_DIR / f"{line}__{cand}_{family}.joblib"
                if path.exists():
                    all_artifacts[line][family][cand] = joblib.load(path)
            # champion algorithm model for this family
            chosen = all_artifacts[line][family].get(algo)
            if chosen is None:
                # fall back to whichever algorithm exists
                chosen = next(iter(all_artifacts[line][family].values()), None)
            if chosen is not None:
                artifacts[line][family] = chosen
            else:
                raise RuntimeError(
                    f"Missing required model artifact for line={line} family={family}; "
                    f"cannot serve pricing for all six lines (R11.3 fail-fast)")

    # R11.3: every line must have all three model families loaded.
    missing = [(line, fam) for line in LINES for fam in FAMILIES
               if fam not in artifacts.get(line, {})]
    if missing:
        raise RuntimeError(f"Pricing artifacts incomplete; missing: {missing}")

    _load_geo()
    _load_cost_indices()
    _load_products()
    _load_loading_factors()
    _loaded = True


def _load_geo() -> None:
    global geo_by_province
    geo_by_province = {}
    if not GEO_RISK_PATH.exists():
        return
    df = pd.read_csv(GEO_RISK_PATH)
    for _, row in df.iterrows():
        geo_by_province[row["province"]] = {c: row[c] for c in df.columns}


def _load_cost_indices() -> None:
    global cost_indices_latest
    cost_indices_latest = {}
    if not COST_INDICES_PATH.exists():
        return
    df = pd.read_csv(COST_INDICES_PATH)
    latest = df.sort_values("month_start").iloc[-1]
    for c in df.columns:
        if c not in ("year", "month", "month_start"):
            cost_indices_latest[c] = float(latest[c])


def _product_json_to_row(p: dict) -> dict:
    """Map snake_case JSON from product-service to snake_case dict for engine use.

    Product-service uses Jackson SNAKE_CASE strategy, so all wire keys are
    snake_case. camelCase input will raise KeyError → fallback to CSV.
    """
    return {
        "product_id": p["product_id"],
        "category": p["category"],
        "product_name": p.get("product_name"),
        "coverage_amount_vnd": p.get("coverage_amount_vnd", 0),
        "deductible_vnd": p.get("deductible_vnd", 0),
        "base_premium_vnd": p.get("base_premium_vnd", 0),
        "admin_fee_vnd": p.get("admin_fee_vnd", 0),
        "active": p.get("active", True),
    }


def _load_products() -> None:
    global products, _products_loaded_at
    try:
        url = f"{PRODUCT_SERVICE_BASE_URL}/internal/products"
        resp = httpx.get(url, timeout=PRODUCT_HTTP_TIMEOUT_SECONDS)
        resp.raise_for_status()
        rows = {}
        for p in resp.json():
            row = _product_json_to_row(p)
            rows[row["product_id"]] = row
        if rows:
            products = rows
            _products_loaded_at = time.monotonic()
            return  # empty response -> fall through to CSV
    except Exception as e:
        warnings.warn(f"Product-service unavailable, falling back to products.csv: {e}")
    _load_products_from_csv()


def _load_products_from_csv() -> None:
    global products, _products_loaded_at
    products = {}
    if not PRODUCTS_PATH.exists():
        return
    df = pd.read_csv(PRODUCTS_PATH)
    for _, row in df.iterrows():
        products[row["product_id"]] = {c: row[c] for c in df.columns}
    _products_loaded_at = time.monotonic()


def _maybe_refresh_products() -> None:
    if time.monotonic() - _products_loaded_at > PRODUCT_CACHE_TTL_SECONDS:
        _load_products()


def _load_loading_factors() -> None:
    global loading_factors, _loading_loaded_at, current_rate_version_id
    try:
        url = f"{PRODUCT_SERVICE_BASE_URL}/internal/loading-factors/current"
        resp = httpx.get(url, timeout=PRODUCT_HTTP_TIMEOUT_SECONDS)
        resp.raise_for_status()
        data = resp.json()
        lf = {row["line"]: float(row["loading_value"]) for row in data}
        if lf:
            loading_factors = lf
            if data and data[0].get("rate_version_id") is not None:
                current_rate_version_id = str(data[0]["rate_version_id"])
            _loading_loaded_at = time.monotonic()
            return
    except Exception as e:
        warnings.warn(f"Loading factors unavailable, defaulting to 1.0: {e}")
    if not loading_factors:
        loading_factors = {ln: 1.0 for ln in LINES}
        _loading_loaded_at = time.monotonic()


def get_loading_factor(line: str) -> float:
    ensure_loaded()
    if time.monotonic() - _loading_loaded_at > PRODUCT_CACHE_TTL_SECONDS:
        _load_loading_factors()
    return float(loading_factors.get(line, 1.0))


def get_current_rate_version_id() -> str | None:
    ensure_loaded()
    return current_rate_version_id


def ensure_loaded() -> None:
    if not _loaded:
        load_artifacts()


def get_line_for_product(product_id: str) -> str:
    ensure_loaded()
    _maybe_refresh_products()
    prod = products.get(product_id)
    if prod is not None:
        return prod["category"]
    upper = product_id.upper()
    for line in LINES:
        if line.upper() in upper:
            return line
    return "health"


def get_product(product_id: str) -> dict:
    ensure_loaded()
    _maybe_refresh_products()
    return products.get(product_id, {})


def get_champion(line: str) -> dict:
    ensure_loaded()
    return champion_config.get("champion_by_line", {}).get(line, {})


def required_columns(line: str) -> list[str]:
    """Return the ordered column list the champion model expects for predict."""
    ensure_loaded()
    cfg = get_champion(line)
    algo = cfg.get("algorithm", "lgb")
    family = cfg.get("family", "tw")
    if family in ("freqsev", "freq_sev"):
        model = all_artifacts[line]["freq"].get(algo) or artifacts[line]["freq"]
    else:
        model = all_artifacts[line][family].get(algo) or artifacts[line][family]
    fn = getattr(model, "feature_name_", None)
    if fn is not None:
        return list(fn)
    # GLM pipeline: union of ColumnTransformer columns.
    prep = model.named_steps["prep"]
    cols: list[str] = []
    for _, _, names in prep.transformers:
        cols.extend(names)
    return cols
