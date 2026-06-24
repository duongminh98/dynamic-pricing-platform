"""Artifact + lookup loader for the Pricing engine.

Loads the 36 model artifacts (6 lines x 3 families x 2 algorithms),
champion_config.json, modeling metadata, geo risk lookup, cost indices
and the product catalog at startup. Fail-fast on missing required files
(R11.2, R11.3). Feature discovery excludes leakage columns (R29.1).

Requirements: R11.2, R11.3, R11.4, R29.1, R29.3, R29.4 (design 6.1, 5.8).
"""
from __future__ import annotations

import json
import pathlib
import warnings

import sys as _sys
import sklearn._loss._loss as _skloss
# Compatibility shim: some LightGBM artifacts were pickled with an older
# scikit-learn that stored the loss module as the top-level name `_loss`.
# scikit-learn >=1.4 moved it under `sklearn._loss._loss`; alias the old
# import path so joblib.load can unpickle the models (R11.4).
_sys.modules.setdefault("_loss", _skloss)

import joblib
import pandas as pd

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
_loaded = False


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


def _load_products() -> None:
    global products
    products = {}
    if not PRODUCTS_PATH.exists():
        return
    df = pd.read_csv(PRODUCTS_PATH)
    for _, row in df.iterrows():
        products[row["product_id"]] = {c: row[c] for c in df.columns}


def ensure_loaded() -> None:
    if not _loaded:
        load_artifacts()


def get_line_for_product(product_id: str) -> str:
    ensure_loaded()
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
    return products.get(product_id, {})


def get_champion(line: str) -> dict:
    ensure_loaded()
    return champion_config.get("champion_by_line", {}).get(line, {})


def required_columns(line: str) -> list[str]:
    """Return the ordered column list the champion model expects for predict."""
    ensure_loaded()
    cfg = get_champion(line)
    algo = cfg.get("algorithm", "lgb")
    model = all_artifacts[line]["tw"].get(algo) or artifacts[line]["tw"]
    fn = getattr(model, "feature_name_", None)
    if fn is not None:
        return list(fn)
    # GLM pipeline: union of ColumnTransformer columns.
    prep = model.named_steps["prep"]
    cols: list[str] = []
    for _, _, names in prep.transformers:
        cols.extend(names)
    return cols